import pytest
import random
import string


ENDPOINT_ROOT = "/"
ENDPOINT_AUDIO = "/audio"
ENDPOINT_TASK = "/task"
ENDPOINT_JOBS = "/jobs"


@pytest.fixture
def random_user_id():
    return "user_" + ''.join(random.choices(string.ascii_lowercase + string.digits, k=10))


@pytest.fixture
def small_wav_file():
    return "assets/harvard.wav"


@pytest.fixture
def small_mp3_file():
    return "assets/hello-there.mp3"


@pytest.fixture
def large_audio_file(tmp_path):
    """Creates file >500MB to trigger validation error."""
    p = tmp_path / "big.raw"
    with open(p, "wb") as f:
        f.seek(501 * 1024 * 1024)
        f.write(b"\0")
    return p


@pytest.fixture
def simple_task():
    return {
        "description": "Need to do something"
    }

@pytest.fixture
def full_task():
    return {
        "description": "Нужно провести встречу с командой разработки по новому проекту",
        "geo": {
            "latitude": 55.7558,
            "longitude": 37.6176
        },
        "name": "Встреча команды разработки",
        "group": "dev-team",
        "data": "priority: high, participants: 5"
    }
