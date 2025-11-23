import requests
from .conftest import *

class TestRoot:
    def test_root_ok(self, base_url):
        response = requests.get(base_url + ENDPOINT_ROOT)

        assert response.status_code == 200
        assert response.text.strip() == "OK"
