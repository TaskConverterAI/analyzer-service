import requests
from .conftest import *

class TestAudioUpload:

    def test_successful_audio_upload(self, base_url, random_user_id, small_wav_file):
        with open(small_wav_file, "rb") as f:
            resp = requests.post(
                base_url + ENDPOINT_AUDIO,
                params={"userID": random_user_id},
                files={"file": f},
                )

        assert resp.status_code == 200
        data = resp.json()
        assert "jobId" in data
        assert data["jobId"]

    def test_missing_user_id(self, base_url, small_wav_file):
        with open(small_wav_file, "rb") as f:
            resp = requests.post(
                base_url + ENDPOINT_AUDIO,
                files={"file": f}
            )

        assert resp.status_code == 400

    def test_missing_file(self, base_url, random_user_id):
        resp = requests.post(
            base_url + ENDPOINT_AUDIO,
            params={"userID": random_user_id},
            files={}
        )
        assert resp.status_code == 400

    def test_large_file_validation(self, base_url, random_user_id, large_audio_file):
        with open(large_audio_file, "rb") as f:
            resp = requests.post(
                base_url + ENDPOINT_AUDIO,
                params={"userID": random_user_id},
                files={"file": f},
                )
        assert resp.status_code == 400

    # remove?
    def test_octet_stream_upload(self, base_url, random_user_id, small_wav_file):
        with open(small_wav_file, "rb") as f:
            resp = requests.post(
                base_url + ENDPOINT_AUDIO,
                params={"userID": random_user_id},
                data=f,
                headers={"Content-Type": "application/octet-stream"}
            )

        assert resp.status_code == 200
        assert "jobId" in resp.json()
