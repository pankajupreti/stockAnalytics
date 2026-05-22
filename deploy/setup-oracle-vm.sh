#!/bin/bash
# =============================================================================
# Stock Analytics - Oracle Cloud VM Setup Script
# Run as root on a fresh Ubuntu 22.04 ARM instance
# Usage: sudo bash setup-oracle-vm.sh
# =============================================================================

set -e

APP_DIR="/opt/stockanalytics"
REPO_URL="https://github.com/pankajupreti/stockAnalytics.git"
DB_PASSWORD="postgres"

echo "=========================================="
echo "  Stock Analytics - VM Setup"
echo "=========================================="

# ---- Step 1: System packages ----
echo ""
echo "[1/9] Installing system packages..."
apt update -y
apt install -y openjdk-17-jdk postgresql postgresql-client rabbitmq-server \
    python3 python3-pip python3-venv nginx git unzip curl

# ---- Step 2: PostgreSQL setup ----
echo ""
echo "[2/9] Configuring PostgreSQL..."
systemctl enable postgresql
systemctl start postgresql

# Set postgres password
sudo -u postgres psql -c "ALTER USER postgres PASSWORD '${DB_PASSWORD}';"

# Allow password auth for local connections
PG_HBA=$(sudo -u postgres psql -t -c "SHOW hba_file;" | xargs)
sed -i 's/local\s\+all\s\+all\s\+peer/local   all             all                                     md5/' "$PG_HBA"
sed -i 's|host\s\+all\s\+all\s\+127.0.0.1/32\s\+ident|host    all             all             127.0.0.1/32            md5|' "$PG_HBA"
systemctl restart postgresql

# ---- Step 3: RabbitMQ setup ----
echo ""
echo "[3/9] Configuring RabbitMQ..."
systemctl enable rabbitmq-server
systemctl start rabbitmq-server
rabbitmq-plugins enable rabbitmq_management 2>/dev/null || true

# ---- Step 4: Clone repo and restore DBs ----
echo ""
echo "[4/9] Cloning repo and restoring databases..."
mkdir -p "$APP_DIR"
cd "$APP_DIR"

if [ ! -d ".git" ]; then
    git clone "$REPO_URL" .
else
    git pull
fi

# Unzip DB dumps
if [ -f "db-dumps.zip" ]; then
    unzip -o db-dumps.zip

    export PGPASSWORD="$DB_PASSWORD"
    for db in portfolio_db announcement_db alert_db results_db tokendb; do
        # Create DB if not exists
        psql -h localhost -p 5432 -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$db'" | grep -q 1 || \
            psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE $db;"

        # Restore dump if file exists and DB is empty
        if [ -f "db-dumps/${db}.dump" ]; then
            TABLE_COUNT=$(psql -h localhost -p 5432 -U postgres -d "$db" -tc "SELECT count(*) FROM pg_tables WHERE schemaname = 'public';" | xargs)
            if [ "$TABLE_COUNT" = "0" ]; then
                echo "  Restoring $db..."
                pg_restore -h localhost -p 5432 -U postgres -d "$db" "db-dumps/${db}.dump" 2>/dev/null || true
            else
                echo "  $db already has tables, skipping restore"
            fi
        fi
    done
    echo "  DB restore complete"
else
    echo "  WARNING: db-dumps.zip not found. Services will create empty tables on startup."
fi

# ---- Step 5: Build Java services ----
echo ""
echo "[5/9] Building Java services..."

JAVA_SERVICES=(
    "discovery-server"
    "oauth"
    "gateway-service"
    "reporting-service"
    "portfolio-service"
    "alert-service"
    "results-service"
    "sheet-import-service"
    "announcement-service"
)

for svc in "${JAVA_SERVICES[@]}"; do
    if [ -d "$APP_DIR/$svc" ]; then
        echo "  Building $svc..."
        cd "$APP_DIR/$svc"
        chmod +x mvnw 2>/dev/null || true
        ./mvnw package -DskipTests -q 2>&1 | tail -1 || echo "  WARNING: $svc build had issues"
    else
        echo "  SKIP: $svc directory not found"
    fi
done

# ---- Step 6: Setup Python results service ----
echo ""
echo "[6/9] Setting up Python results service..."
cd "$APP_DIR/results-service-python"
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt -q
deactivate

# ---- Step 7: Create systemd services ----
echo ""
echo "[7/9] Creating systemd services..."

# Service definitions: name|directory|jar-pattern|port
declare -A SVC_PORTS=(
    ["discovery"]="8761"
    ["oauth"]="8080"
    ["gateway"]="8082"
    ["reporting"]="8083"
    ["portfolio"]="8084"
    ["alert"]="8087"
    ["results-java"]="8088"
    ["sheet-import"]="8091"
    ["announcement"]="8092"
)

declare -A SVC_DIRS=(
    ["discovery"]="discovery-server"
    ["oauth"]="oauth"
    ["gateway"]="gateway-service"
    ["reporting"]="reporting-service"
    ["portfolio"]="portfolio-service"
    ["alert"]="alert-service"
    ["results-java"]="results-service"
    ["sheet-import"]="sheet-import-service"
    ["announcement"]="announcement-service"
)

for svc in "${!SVC_DIRS[@]}"; do
    dir="${SVC_DIRS[$svc]}"
    port="${SVC_PORTS[$svc]}"
    jar=$(find "$APP_DIR/$dir/target" -name "*.jar" -not -name "*original*" 2>/dev/null | head -1)

    if [ -z "$jar" ]; then
        echo "  SKIP: No jar found for $svc"
        continue
    fi

    # Discovery must start before others
    if [ "$svc" = "discovery" ]; then
        AFTER="After=network.target postgresql.service"
    else
        AFTER="After=network.target postgresql.service stockanalytics-discovery.service"
    fi

    cat > "/etc/systemd/system/stockanalytics-${svc}.service" <<SVCEOF
[Unit]
Description=StockAnalytics - ${svc}
${AFTER}

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}/${dir}
ExecStart=/usr/bin/java -jar ${jar} --server.port=${port}
Restart=always
RestartSec=15
Environment=SPRING_PROFILES_ACTIVE=default

[Install]
WantedBy=multi-user.target
SVCEOF

    systemctl enable "stockanalytics-${svc}" 2>/dev/null
    echo "  Created stockanalytics-${svc}.service (port ${port})"
done

# Python results service
cat > /etc/systemd/system/stockanalytics-results-python.service <<PYEOF
[Unit]
Description=StockAnalytics - Results Python Service
After=network.target postgresql.service

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}/results-service-python
ExecStart=${APP_DIR}/results-service-python/venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
Restart=always
RestartSec=15

[Install]
WantedBy=multi-user.target
PYEOF

systemctl enable stockanalytics-results-python 2>/dev/null
echo "  Created stockanalytics-results-python.service (port 8090)"

systemctl daemon-reload

# ---- Step 8: Nginx reverse proxy ----
echo ""
echo "[8/9] Configuring Nginx..."

cat > /etc/nginx/sites-available/stockanalytics <<'NGINXEOF'
server {
    listen 80;
    server_name _;

    # Increase timeouts for slow API calls
    proxy_read_timeout 120s;
    proxy_send_timeout 120s;
    proxy_connect_timeout 30s;

    # Max upload size (for file uploads if any)
    client_max_body_size 10M;

    location / {
        proxy_pass http://localhost:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINXEOF

# Remove default site, enable ours
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/stockanalytics /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
systemctl enable nginx

# ---- Step 9: Firewall ----
echo ""
echo "[9/9] Configuring firewall..."
iptables -I INPUT -p tcp --dport 80 -j ACCEPT
iptables -I INPUT -p tcp --dport 443 -j ACCEPT
apt install -y iptables-persistent 2>/dev/null || true
netfilter-persistent save 2>/dev/null || true

# ---- Create helper scripts ----
echo ""
echo "Creating helper scripts..."

# Start all services (discovery first, then rest)
cat > "$APP_DIR/deploy/start-all.sh" <<'STARTEOF'
#!/bin/bash
echo "Starting Discovery (Eureka)..."
sudo systemctl start stockanalytics-discovery
echo "Waiting for Eureka to be ready..."
sleep 30

echo "Starting all other services..."
for svc in oauth gateway reporting portfolio alert results-java sheet-import announcement results-python; do
    sudo systemctl start "stockanalytics-${svc}"
    echo "  Started ${svc}"
done
echo "All services started. Check status with: sudo bash deploy/status.sh"
STARTEOF

# Stop all services
cat > "$APP_DIR/deploy/stop-all.sh" <<'STOPEOF'
#!/bin/bash
echo "Stopping all services..."
for svc in results-python announcement sheet-import results-java alert portfolio reporting gateway oauth discovery; do
    sudo systemctl stop "stockanalytics-${svc}" 2>/dev/null
    echo "  Stopped ${svc}"
done
echo "All services stopped."
STOPEOF

# Status check
cat > "$APP_DIR/deploy/status.sh" <<'STATUSEOF'
#!/bin/bash
echo "=========================================="
echo "  Service Status"
echo "=========================================="
printf "%-25s %-12s %s\n" "SERVICE" "STATUS" "PORT"
printf "%-25s %-12s %s\n" "-------" "------" "----"
for svc in discovery oauth gateway reporting portfolio alert results-java sheet-import announcement results-python; do
    status=$(systemctl is-active "stockanalytics-${svc}" 2>/dev/null || echo "not-found")
    case $svc in
        discovery) port=8761 ;;
        oauth) port=8080 ;;
        gateway) port=8082 ;;
        reporting) port=8083 ;;
        portfolio) port=8084 ;;
        alert) port=8087 ;;
        results-java) port=8088 ;;
        sheet-import) port=8091 ;;
        announcement) port=8092 ;;
        results-python) port=8090 ;;
    esac
    if [ "$status" = "active" ]; then
        printf "%-25s \033[32m%-12s\033[0m %s\n" "$svc" "$status" "$port"
    else
        printf "%-25s \033[31m%-12s\033[0m %s\n" "$svc" "$status" "$port"
    fi
done
echo ""
echo "Nginx: $(systemctl is-active nginx)"
echo "PostgreSQL: $(systemctl is-active postgresql)"
echo "RabbitMQ: $(systemctl is-active rabbitmq-server)"
STATUSEOF

# Restart single service
cat > "$APP_DIR/deploy/restart-service.sh" <<'RESTARTEOF'
#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: bash deploy/restart-service.sh <service-name>"
    echo "Services: discovery oauth gateway reporting portfolio alert results-java sheet-import announcement results-python"
    exit 1
fi
echo "Restarting stockanalytics-$1..."
sudo systemctl restart "stockanalytics-$1"
sudo systemctl status "stockanalytics-$1" --no-pager -l | head -5
RESTARTEOF

# View logs
cat > "$APP_DIR/deploy/logs.sh" <<'LOGSEOF'
#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: bash deploy/logs.sh <service-name> [lines]"
    echo "Services: discovery oauth gateway reporting portfolio alert results-java sheet-import announcement results-python"
    exit 1
fi
lines=${2:-50}
sudo journalctl -u "stockanalytics-$1" -n "$lines" --no-pager -f
LOGSEOF

# SSL setup helper
cat > "$APP_DIR/deploy/setup-ssl.sh" <<'SSLEOF'
#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: sudo bash deploy/setup-ssl.sh yourdomain.com"
    exit 1
fi
DOMAIN=$1
echo "Setting up SSL for $DOMAIN..."

# Update nginx server_name
sed -i "s/server_name _;/server_name ${DOMAIN};/" /etc/nginx/sites-available/stockanalytics
nginx -t && systemctl reload nginx

# Install certbot and get cert
apt install -y certbot python3-certbot-nginx
certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --email admin@${DOMAIN}

echo "SSL setup complete! Site available at https://${DOMAIN}"
echo ""
echo "IMPORTANT: Update Google OAuth credentials:"
echo "  1. Go to Google Cloud Console → Credentials"
echo "  2. Add https://${DOMAIN} to Authorized JavaScript origins"
echo "  3. Add https://${DOMAIN}/login/oauth2/code/google to Authorized redirect URIs"
SSLEOF

chmod +x "$APP_DIR/deploy/"*.sh

# ---- Done ----
echo ""
echo "=========================================="
echo "  Setup Complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "  1. Start services:     sudo bash deploy/start-all.sh"
echo "  2. Check status:       sudo bash deploy/status.sh"
echo "  3. View logs:          sudo bash deploy/logs.sh <service>"
echo "  4. Restart a service:  sudo bash deploy/restart-service.sh <service>"
echo "  5. Setup SSL:          sudo bash deploy/setup-ssl.sh yourdomain.com"
echo ""
echo "Your app will be available at: http://$(curl -s ifconfig.me 2>/dev/null || echo '<your-ip>')"
echo ""
