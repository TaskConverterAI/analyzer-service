import requests
from .conftest import *

class TestJobDetails:
    def test_job_status_ok(self, base_url, random_user_id, small_wav_file):
        # create job
        with open(small_wav_file, "rb") as f:
            submit = requests.post(
                base_url + ENDPOINT_AUDIO,
                params={"userID": random_user_id},
                files={"file": f},
                )
        job_id = submit.json()["jobId"]

        resp = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}")
        assert resp.status_code == 200

        data = resp.json()
        assert data["jobId"] == job_id
        assert data["submitterUserId"] == random_user_id
        assert data["type"] in ("AUDIO", "TASK")

    def test_job_not_found(self, base_url):
        resp = requests.get(base_url + ENDPOINT_JOBS + "/nonexistent-123")
        assert resp.status_code == 404
