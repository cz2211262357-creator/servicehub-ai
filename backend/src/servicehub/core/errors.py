from typing import Any
from uuid import uuid4

from fastapi import Request
from fastapi.responses import JSONResponse


class BusinessError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 400, details: Any = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = details


def error_response(request: Request, code: str, message: str, status: int, details=None):
    body = {
        "code": code,
        "message": message,
        "request_id": getattr(request.state, "request_id", uuid4().hex),
    }
    if details is not None:
        body["details"] = details
    return JSONResponse({"error": body}, status_code=status)


async def business_error_handler(request: Request, error: BusinessError):
    return error_response(request, error.code, error.message, error.status_code, error.details)
