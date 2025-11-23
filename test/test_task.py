import requests
from .conftest import *


class TestTaskSubmission:
    def test_simple_task_upload(self, base_url, random_user_id, simple_task):
        resp = requests.post(
            base_url + ENDPOINT_TASK,
            params={"userID": random_user_id},
            json=simple_task
        )

        assert resp.status_code == 200
        assert "jobId" in resp.json()

    def test_full_task_upload(self, base_url, random_user_id, full_task):
        resp = requests.post(
            base_url + ENDPOINT_TASK,
            params={"userID": random_user_id},
            json=full_task
        )

        assert resp.status_code == 200
        assert "jobId" in resp.json()

    def test_missing_user_id(self, base_url, full_task):
        resp = requests.post(
            base_url + ENDPOINT_TASK,
            json=full_task
        )

        assert resp.status_code == 400

    def test_missing_description(self, base_url, random_user_id, full_task):
        full_task.pop("description")
        resp = requests.post(
            base_url + ENDPOINT_TASK,
            params={"userID": random_user_id},
            json=full_task
        )

        assert resp.status_code == 400
