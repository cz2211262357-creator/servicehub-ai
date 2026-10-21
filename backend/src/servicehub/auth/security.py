import hashlib
import secrets
from functools import lru_cache

from pwdlib import PasswordHash

password_hasher = PasswordHash.recommended()


def hash_password(value: str) -> str:
    return password_hasher.hash(value)


@lru_cache(maxsize=1)
def dummy_password_hash() -> str:
    return hash_password(secrets.token_urlsafe(32))


def verify_password(value: str, hashed: str | None) -> bool:
    try:
        return password_hasher.verify(value, hashed or dummy_password_hash())
    except Exception:
        return False


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(32)
