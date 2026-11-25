"""M1 认证路径负载测试（Locust）。

用法见同目录 README.md。压测账号来自 E2E 种子 `frontend/e2e/.state.json`
（环境变量 E2E_STATE 可覆盖路径）；种子未生成时回退到 E2E_PERF_USER/E2E_PERF_PASS。
"""
import json
import os
import random

from locust import HttpUser, between, task

STATE_FILE = os.environ.get(
    "E2E_STATE",
    os.path.join(os.path.dirname(__file__), "..", "frontend", "e2e", ".state.json"),
)
DEFAULT_USER = os.environ.get("E2E_PERF_USER", "E2E学生甲")
DEFAULT_PASS = os.environ.get("E2E_PERF_PASS", "Passw0rd!23")


def _load_credentials() -> list[tuple[str, str]]:
    """优先用种子里的全部账号轮换，避免单账号审计热行。"""
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            state = json.load(f)
        accounts = [
            (a["username"], a["password"])
            for a in (
                state.get("adminNoSchool"),
                state.get("adminWithSchool"),
                state.get("teacher"),
                state.get("student"),
            )
            if a
        ]
        return accounts or [(DEFAULT_USER, DEFAULT_PASS)]
    except (OSError, KeyError, ValueError):
        return [(DEFAULT_USER, DEFAULT_PASS)]


CREDENTIALS = _load_credentials()


class M1AuthUser(HttpUser):
    """模拟登录 → 鉴权访问的认证用户。"""

    wait_time = between(0.5, 1.5)

    def on_start(self) -> None:
        self._token: str | None = None

    @task(2)
    def login_ok(self):
        username, password = random.choice(CREDENTIALS)
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": username, "password": password},
            name="POST /auth/login [ok]",
            catch_response=True,
        ) as response:
            if response.status_code == 200:
                self._token = response.json().get("token")
                response.success()
            else:
                response.failure(f"login failed: {response.status_code}")

    @task(1)
    def login_wrong_password(self):
        username, _ = random.choice(CREDENTIALS)
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": username, "password": "wrong-password-000"},
            name="POST /auth/login [bad password]",
            catch_response=True,
        ) as response:
            # 失败统一 401 UNAUTHORIZED；其余状态码（500/泄露差异）均为问题
            if response.status_code == 401:
                response.success()
            else:
                response.failure(f"unexpected status: {response.status_code}")

    @task(3)
    def me(self):
        if not self._token:
            username, password = random.choice(CREDENTIALS)
            response = self.client.post(
                "/api/v1/auth/login", json={"username": username, "password": password}
            )
            self._token = response.json().get("token") if response.status_code == 200 else None
            if not self._token:
                return
        with self.client.get(
            "/api/v1/auth/me",
            headers={"Authorization": f"Bearer {self._token}"},
            name="GET /auth/me",
            catch_response=True,
        ) as response:
            if response.status_code == 200:
                response.success()
            elif response.status_code == 401:
                # token 2h 有效期内不该 401；出现即失败
                response.failure("token rejected within TTL")
            else:
                response.failure(f"unexpected status: {response.status_code}")

    @task(1)
    def public_schools(self):
        self.client.get("/api/v1/public/schools", name="GET /public/schools [public]")
