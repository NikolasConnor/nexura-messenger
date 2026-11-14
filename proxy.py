import asyncio
from urllib.parse import urlencode

import httpx
import websockets
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from starlette.responses import JSONResponse, Response

UPSTREAM_HOST = "lyhzawblxcvpgpjrkspc.supabase.co"
UPSTREAM_ORIGIN = f"https://{UPSTREAM_HOST}"
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host",
}

app = FastAPI(title="Nexura API gateway", docs_url=None, redoc_url=None)
http = httpx.AsyncClient(
    timeout=httpx.Timeout(connect=15.0, read=90.0, write=90.0, pool=15.0),
    follow_redirects=False,
    limits=httpx.Limits(max_connections=150, max_keepalive_connections=30),
)


@app.on_event("shutdown")
async def close_http_client():
    await http.aclose()


@app.get("/healthz")
async def health():
    return {"ok": True, "service": "nexura-api-gateway"}


def rewrite_upstream_host(value: str, public_host: str) -> str:
    # OAuth redirects may contain the Supabase callback URL inside an encoded query.
    return value.replace(UPSTREAM_HOST, public_host)


@app.api_route(
    "/{path:path}",
    methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"],
)
async def forward_http(path: str, request: Request):
    if path == "healthz":
        return await health()

    query = request.url.query
    target = f"{UPSTREAM_ORIGIN}/{path}" + (f"?{query}" if query else "")
    headers = {
        key: value
        for key, value in request.headers.items()
        if key.lower() not in HOP_BY_HOP and key.lower() != "content-length"
    }

    try:
        upstream = await http.request(
            request.method,
            target,
            headers=headers,
            content=await request.body(),
        )
    except httpx.HTTPError:
        return JSONResponse(
            {"message": "Nexura gateway could not reach the messaging service"},
            status_code=502,
        )

    public_host = request.url.netloc
    response_headers = []
    for key, value in upstream.headers.multi_items():
        lower = key.lower()
        if lower in HOP_BY_HOP or lower in {"content-length", "content-encoding"}:
            continue
        if lower == "location":
            value = rewrite_upstream_host(value, public_host)
        elif lower == "set-cookie":
            value = value.replace(f"Domain={UPSTREAM_HOST}", f"Domain={public_host}")
        response_headers.append((lower.encode("latin-1"), value.encode("latin-1")))

    response_headers.append((b"content-length", str(len(upstream.content)).encode("ascii")))
    result = Response(content=upstream.content, status_code=upstream.status_code)
    result.raw_headers = response_headers
    return result


@app.websocket("/realtime/{path:path}")
async def forward_realtime(websocket: WebSocket, path: str):
    await websocket.accept()
    query = websocket.url.query
    target = f"wss://{UPSTREAM_HOST}/realtime/{path}" + (f"?{query}" if query else "")
    forwarded_headers = {}
    for key in ("origin", "user-agent", "authorization", "apikey"):
        value = websocket.headers.get(key)
        if value:
            forwarded_headers[key] = value

    try:
        async with websockets.connect(
            target,
            additional_headers=forwarded_headers,
            ping_interval=25,
            ping_timeout=25,
            max_size=None,
            open_timeout=20,
        ) as upstream:
            async def client_to_upstream():
                while True:
                    packet = await websocket.receive()
                    if packet["type"] == "websocket.disconnect":
                        return
                    if packet.get("text") is not None:
                        await upstream.send(packet["text"])
                    elif packet.get("bytes") is not None:
                        await upstream.send(packet["bytes"])

            async def upstream_to_client():
                async for packet in upstream:
                    if isinstance(packet, bytes):
                        await websocket.send_bytes(packet)
                    else:
                        await websocket.send_text(packet)

            tasks = {
                asyncio.create_task(client_to_upstream()),
                asyncio.create_task(upstream_to_client()),
            }
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                exception = task.exception()
                if exception:
                    raise exception
    except WebSocketDisconnect:
        pass
    except Exception:
        try:
            await websocket.close(code=1011, reason="Realtime gateway connection failed")
        except Exception:
            pass
