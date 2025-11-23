import requests
from .conftest import *
import time

class TestJobResult:
    @pytest.mark.parametrize("audio_fixture", [
        "small_mp3_file",
        "small_wav_file",
    ])
    def test_audio_result(self, request, base_url, random_user_id, audio_fixture):
        file = request.getfixturevalue(audio_fixture)
        # Submit audio task
        with open(file, "rb") as f:
            submit = requests.post(
                base_url + ENDPOINT_AUDIO,
                params={"userID": random_user_id},
                files={"file": f},
            )
        job_id = submit.json()["jobId"]

        # Poll until SUCCEEDED (or timeout after ~10s)
        for _ in range(20):
            status = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}").json()
            if status["status"] == "SUCCEEDED":
                break
            time.sleep(0.5)

        # First request must succeed
        first = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}/result")
        assert first.status_code == 200
        assert isinstance(first.json(), list)  # audio returns list of utterances

        # Second request — result must not exist anymore
        second = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}/result")
        assert second.status_code == 404

    def test_task_result(self, base_url, random_user_id, simple_task):
        resp = requests.post(
            base_url + ENDPOINT_TASK,
            params={"userID": random_user_id},
            json=simple_task
        )
        job_id = resp.json()["jobId"]

        # Poll until SUCCEEDED (or timeout after ~10s)
        for _ in range(20):
            status = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}").json()
            if status["status"] == "SUCCEEDED":
                break
            assert status["status"] != "FAILED"
            time.sleep(0.5)

        # First request must succeed
        first = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}/result")
        assert first.status_code == 200

        time.sleep(1)

        # Second request — result must not exist anymore
        second = requests.get(base_url + ENDPOINT_JOBS + f"/{job_id}/result")
        assert second.status_code == 404
