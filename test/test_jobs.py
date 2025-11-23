import requests
from .conftest import *

class TestJobsList:
    def test_jobs_list_no_user_filter(self, base_url):
        resp = requests.get(base_url + ENDPOINT_JOBS)
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_jobs_list_filtered(self, base_url, random_user_id):
        resp = requests.get(base_url + ENDPOINT_JOBS, params={"userID": random_user_id})
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)
