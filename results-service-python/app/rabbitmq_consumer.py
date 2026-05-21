"""
RabbitMQ Consumer for Results Fetch Events.

Listens for financial results announcements from announcement-service.
When an event is received, attempts to fetch data from Screener.in.
If fetch fails (Screener doesn't have data yet), sends to retry queue.

Retry Strategy (PERSISTENT - survives restarts):
- Attempt 1: Immediately when message received
- Attempt 2-5: Every 6 hours (via delayed retry queue)
- After 5 attempts: Move to Dead Letter Queue
"""

import asyncio
import json
import logging
import os
from datetime import datetime
from typing import Optional

import aio_pika
from aio_pika import Message, DeliveryMode

logger = logging.getLogger(__name__)

# Configuration from environment or defaults
RABBITMQ_HOST = os.getenv("RABBITMQ_HOST", "localhost")
RABBITMQ_PORT = int(os.getenv("RABBITMQ_PORT", "5672"))
RABBITMQ_USER = os.getenv("RABBITMQ_USERNAME", "guest")
RABBITMQ_PASS = os.getenv("RABBITMQ_PASSWORD", "guest")

EXCHANGE_NAME = "results.exchange"
QUEUE_NAME = "results.fetch.queue"
ROUTING_KEY = "results.fetch"

# Dead Letter Queue configuration
DLX_EXCHANGE = "results.dlx.exchange"
DLQ_QUEUE = "results.fetch.dlq"
DLQ_ROUTING_KEY = "results.fetch.dead"

# Delayed Retry Queue configuration
# Messages wait here for 5 hours, then automatically move back to main queue
RETRY_QUEUE = "results.fetch.retry"
RETRY_ROUTING_KEY = "results.fetch.retry"
RETRY_DELAY_MS = 5 * 60 * 60 * 1000  # 5 hours in milliseconds


class ResultsQueueConsumer:
    """
    Consumes results fetch events from RabbitMQ.
    Uses a persistent delayed retry queue for retries that survive restarts.
    """

    def __init__(self, screener_scraper, database):
        self.screener_scraper = screener_scraper
        self.database = database
        self.connection: Optional[aio_pika.RobustConnection] = None
        self.channel: Optional[aio_pika.Channel] = None
        self.exchange = None
        self._running = False

    async def connect(self) -> bool:
        """Connect to RabbitMQ and declare all queues."""
        try:
            self.connection = await aio_pika.connect_robust(
                host=RABBITMQ_HOST,
                port=RABBITMQ_PORT,
                login=RABBITMQ_USER,
                password=RABBITMQ_PASS,
            )
            self.channel = await self.connection.channel()

            # Limit to 1 message at a time to avoid overwhelming the channel
            await self.channel.set_qos(prefetch_count=1)

            # Declare main exchange
            self.exchange = await self.channel.declare_exchange(
                EXCHANGE_NAME,
                aio_pika.ExchangeType.DIRECT,
                durable=True
            )

            # Declare Dead Letter Exchange
            dlx_exchange = await self.channel.declare_exchange(
                DLX_EXCHANGE,
                aio_pika.ExchangeType.DIRECT,
                durable=True
            )

            # Declare main queue with DLX configuration
            queue = await self.channel.declare_queue(
                QUEUE_NAME,
                durable=True,
                arguments={
                    "x-message-ttl": 604800000,  # 7 days max
                    "x-dead-letter-exchange": DLX_EXCHANGE,
                    "x-dead-letter-routing-key": DLQ_ROUTING_KEY
                }
            )

            # Declare Dead Letter Queue (30 days retention)
            dlq = await self.channel.declare_queue(
                DLQ_QUEUE,
                durable=True,
                arguments={
                    "x-message-ttl": 2592000000  # 30 days
                }
            )

            # Declare Delayed Retry Queue
            # Messages wait here for 5 hours, then dead-letter back to main queue
            retry_queue = await self.channel.declare_queue(
                RETRY_QUEUE,
                durable=True,
                arguments={
                    "x-message-ttl": RETRY_DELAY_MS,  # 5 hours
                    "x-dead-letter-exchange": EXCHANGE_NAME,  # Back to main exchange
                    "x-dead-letter-routing-key": ROUTING_KEY  # With main routing key
                }
            )

            # Bind queues to exchanges
            await queue.bind(self.exchange, routing_key=ROUTING_KEY)
            await dlq.bind(dlx_exchange, routing_key=DLQ_ROUTING_KEY)
            await retry_queue.bind(self.exchange, routing_key=RETRY_ROUTING_KEY)

            logger.info(f"Connected to RabbitMQ at {RABBITMQ_HOST}:{RABBITMQ_PORT}")
            logger.info(f"Queues: main={QUEUE_NAME}, retry={RETRY_QUEUE} (5h delay), dlq={DLQ_QUEUE}")
            return True

        except Exception as e:
            logger.error(f"Failed to connect to RabbitMQ: {e}")
            return False

    async def start_consuming(self):
        """Start consuming messages from the queue with auto-reconnect."""
        self._running = True

        while self._running:
            try:
                if not self.channel or self.channel.is_closed:
                    logger.info("Connecting to RabbitMQ...")
                    connected = await self.connect()
                    if not connected:
                        logger.error("Cannot connect to RabbitMQ, retrying in 10 seconds...")
                        await asyncio.sleep(10)
                        continue

                queue = await self.channel.get_queue(QUEUE_NAME)
                logger.info(f"Started consuming from queue: {QUEUE_NAME}")

                # Use no_ack=False so we manually ack/nack messages
                async with queue.iterator(no_ack=False) as queue_iter:
                    async for message in queue_iter:
                        if not self._running:
                            break
                        try:
                            await self._process_message(message)
                        except Exception as e:
                            logger.error(f"Error processing message: {e}", exc_info=True)
                            # Requeue on unexpected errors
                            try:
                                await message.nack(requeue=True)
                            except Exception:
                                pass

            except aio_pika.exceptions.ChannelClosed as e:
                logger.warning(f"Channel closed: {e}, reconnecting...")
                self.channel = None
                await asyncio.sleep(5)
            except aio_pika.exceptions.ConnectionClosed as e:
                logger.warning(f"Connection closed: {e}, reconnecting...")
                self.channel = None
                self.connection = None
                await asyncio.sleep(5)
            except Exception as e:
                error_name = type(e).__name__
                if "ChannelInvalidStateError" in error_name or "Invalid" in str(e):
                    logger.warning(f"Channel invalid: {e}, reconnecting...")
                    self.channel = None
                    await asyncio.sleep(5)
                else:
                    logger.error(f"Error in consumer: {e}", exc_info=True)
                    await asyncio.sleep(10)

    async def _safe_ack(self, message: aio_pika.IncomingMessage):
        """Safely acknowledge a message, handling channel errors."""
        try:
            await message.ack()
        except Exception as e:
            logger.warning(f"Failed to ack message (channel may be closed): {e}")

    async def _safe_nack(self, message: aio_pika.IncomingMessage, requeue: bool = True):
        """Safely nack a message, handling channel errors."""
        try:
            await message.nack(requeue=requeue)
        except Exception as e:
            logger.warning(f"Failed to nack message (channel may be closed): {e}")

    async def _safe_reject(self, message: aio_pika.IncomingMessage, requeue: bool = False):
        """Safely reject a message, handling channel errors."""
        try:
            await message.reject(requeue=requeue)
        except Exception as e:
            logger.warning(f"Failed to reject message (channel may be closed): {e}")

    async def _process_message(self, message: aio_pika.IncomingMessage):
        """Process a single message from the queue."""
        ticker = "UNKNOWN"
        try:
            # Parse the event
            body = message.body.decode()
            event = json.loads(body)

            ticker = event.get("ticker", "").upper()
            attempt_number = event.get("attemptNumber", 1)
            max_attempts = event.get("maxAttempts", 5)
            company_name = event.get("companyName", "")
            expected_quarter = event.get("expectedQuarter")  # e.g., "Q3 FY2026"

            logger.info(f"Processing results fetch for {ticker} (attempt {attempt_number}/{max_attempts}, expected: {expected_quarter})")

            # Add delay between requests to avoid rate limiting
            await asyncio.sleep(3)  # 3 second delay between requests

            # Try to fetch from Screener.in
            success, fail_reason = await self._fetch_and_cache_results(ticker, expected_quarter)

            if success:
                logger.info(f"Successfully fetched results for {ticker}")
            else:
                # Track the failure reason across retries
                event["lastFailReason"] = fail_reason or "Unknown"
                # Screener doesn't have data yet, schedule retry
                if attempt_number < max_attempts:
                    await self._schedule_retry(event)
                else:
                    # Max attempts reached - send to Dead Letter Queue
                    detail = fail_reason or "Unknown"
                    logger.warning(f"Max attempts ({max_attempts}) reached for {ticker}, moving to DLQ. Reason: {detail}")
                    await self._send_to_dlq(event, f"Max retries exhausted: {detail}")

            # Acknowledge message after successful processing
            await self._safe_ack(message)

        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON in message: {e}")
            await self._safe_reject(message, requeue=False)
        except Exception as e:
            logger.error(f"Error processing message for {ticker}: {e}")
            await self._safe_nack(message, requeue=True)

    async def _fetch_and_cache_results(self, ticker: str, expected_quarter: str = None) -> tuple:
        """
        Fetch results from Screener.in and cache them.
        Returns (True, None) if fresh data was found AND the expected quarter is present.
        Returns (False, reason) if fetch failed or expected quarter is missing.

        Args:
            ticker: Stock ticker symbol
            expected_quarter: Expected quarter label (e.g., "Q3 FY2026") from announcement
        """
        try:
            result = await self.screener_scraper.fetch_quarterly_results(ticker)

            if result.get("success") and result.get("results"):
                results = result["results"]

                # Check if we got meaningful new data
                if len(results) > 0:
                    # Get the latest quarter from results
                    latest = results[0] if results else None
                    if latest:
                        latest_quarter = latest.get("quarterLabel", "")
                        logger.info(f"Fetched {len(results)} quarters for {ticker}, latest: {latest_quarter}")

                        # Verify expected quarter is present (if specified)
                        if expected_quarter:
                            # Normalize for comparison (e.g., "Q3 FY2026" vs "Q3 FY2026")
                            expected_normalized = expected_quarter.upper().replace(" ", "")
                            latest_normalized = latest_quarter.upper().replace(" ", "")

                            if expected_normalized != latest_normalized:
                                reason = f"Quarter mismatch: expected {expected_quarter}, got {latest_quarter}"
                                logger.warning(
                                    f"{reason} for {ticker} - Screener not updated yet, will retry"
                                )
                                return False, reason

                    # Save to database cache
                    saved = await self.database.save_results(ticker, results)
                    if saved:
                        logger.info(f"Cached {len(results)} quarters for {ticker}")
                        return True, None

            # Determine reason for no data
            error_msg = result.get("error", "") if result else ""
            if error_msg:
                reason = f"Screener error: {error_msg}"
            elif not result.get("success"):
                reason = f"Screener fetch failed (ticker not found or page error)"
            else:
                reason = "No results data returned from Screener"
            logger.info(f"No new results found for {ticker} on Screener.in: {reason}")
            return False, reason

        except Exception as e:
            reason = f"Exception: {str(e)}"
            logger.error(f"Error fetching results for {ticker}: {e}")
            return False, reason

    async def _schedule_retry(self, event: dict):
        """
        Schedule a retry by publishing to the delayed retry queue.
        The message will wait in the retry queue for 5 hours (TTL),
        then automatically dead-letter back to the main queue.

        This is PERSISTENT - survives service restarts!
        """
        ticker = event.get("ticker", "")
        attempt_number = event.get("attemptNumber", 1)
        max_attempts = event.get("maxAttempts", 5)

        # Increment attempt
        event["attemptNumber"] = attempt_number + 1
        event["retryScheduledAt"] = datetime.now().isoformat()

        next_attempt = attempt_number + 1
        delay_hours = RETRY_DELAY_MS / (1000 * 60 * 60)

        logger.info(f"Scheduling retry {next_attempt}/{max_attempts} for {ticker} in {delay_hours:.1f} hours (persistent)")

        try:
            if self.exchange:
                message = Message(
                    body=json.dumps(event).encode(),
                    delivery_mode=DeliveryMode.PERSISTENT,
                    content_type="application/json"
                )

                # Publish to retry queue - it will automatically come back after TTL expires
                await self.exchange.publish(message, routing_key=RETRY_ROUTING_KEY)
                logger.info(f"Message for {ticker} sent to retry queue (will retry in {delay_hours:.1f}h)")
            else:
                logger.error(f"Cannot schedule retry: exchange not available")

        except Exception as e:
            logger.error(f"Failed to schedule retry for {ticker}: {e}")

    async def _send_to_dlq(self, event: dict, reason: str):
        """Send failed message to Dead Letter Queue."""
        ticker = event.get("ticker", "")

        try:
            if self.channel:
                dlx_exchange = await self.channel.get_exchange(DLX_EXCHANGE)

                # Add failure metadata
                event["failureReason"] = reason
                event["failedAt"] = datetime.now().isoformat()

                message = Message(
                    body=json.dumps(event).encode(),
                    delivery_mode=DeliveryMode.PERSISTENT,
                    content_type="application/json"
                )

                await dlx_exchange.publish(message, routing_key=DLQ_ROUTING_KEY)
                logger.info(f"Sent failed message for {ticker} to DLQ: {reason}")
            else:
                logger.error(f"Cannot send to DLQ: channel not available")

        except Exception as e:
            logger.error(f"Failed to send message to DLQ for {ticker}: {e}")

    async def stop(self):
        """Stop consuming and close connection."""
        self._running = False
        if self.connection:
            await self.connection.close()
            logger.info("Disconnected from RabbitMQ")


async def run_consumer(screener_scraper, database):
    """
    Run the RabbitMQ consumer as a background task.
    Call this from the main application startup.
    """
    consumer = ResultsQueueConsumer(screener_scraper, database)

    connected = await consumer.connect()
    if connected:
        # Run in background
        asyncio.create_task(consumer.start_consuming())
        logger.info("RabbitMQ consumer started in background")
        return consumer
    else:
        logger.warning("RabbitMQ consumer not started: connection failed")
        return None
