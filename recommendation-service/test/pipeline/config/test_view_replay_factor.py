import pytest

from pipeline.config.constants import MAX_COUNTED_VIEW_REPLAYS, view_replay_factor

pytestmark = pytest.mark.unit


def test_replay_factor_is_one_for_the_first_view():
    assert view_replay_factor(1) == 1.0


@pytest.mark.parametrize("replay_count", range(2, MAX_COUNTED_VIEW_REPLAYS + 1))
def test_replay_factor_is_one_over_replay_count_up_to_the_cap(replay_count):
    assert view_replay_factor(replay_count) == pytest.approx(1.0 / replay_count)


@pytest.mark.parametrize("replay_count", [MAX_COUNTED_VIEW_REPLAYS + 1, MAX_COUNTED_VIEW_REPLAYS + 2, 50, 10**9])
def test_replay_factor_is_zero_above_the_cap(replay_count):
    assert view_replay_factor(replay_count) == 0.0


@pytest.mark.parametrize("replay_count", [0, -1, -50])
def test_replay_factor_treats_replay_count_below_one_as_one(replay_count):
    assert view_replay_factor(replay_count) == 1.0


def test_max_counted_view_replays_is_five():
    assert MAX_COUNTED_VIEW_REPLAYS == 5


@pytest.mark.parametrize("replay_count", [None, "3", 2.0, True])
def test_replay_factor_rejects_a_non_integer_replay_count(replay_count):
    with pytest.raises(TypeError):
        view_replay_factor(replay_count)
