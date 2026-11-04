import uvicorn

from Backend.config import Telegram
from Backend.fastapi.main import app

#----- Uvicorn server bound to the FastAPI app
Port = Telegram.PORT
config = uvicorn.Config(
    app=app,
    host="0.0.0.0",
    port=Port,
    loop="uvloop",
    http="httptools",
    #----- Behind Cloudflare Tunnel / a reverse proxy: trust X-Forwarded-* so
    #      client IPs and https scheme are right, and don't send "server: uvicorn".
    proxy_headers=True,
    forwarded_allow_ips="*",
    server_header=False,
    timeout_keep_alive=30,
    timeout_graceful_shutdown=5,
)
server = uvicorn.Server(config)
