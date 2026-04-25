"""
Run the Results Service.
"""
import uvicorn

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8090,
        reload=False,  # Disabled due to Python 3.13 Windows issue
        log_level="info"
    )
