import pytest

from shared.config.env import positive_int_env

pytestmark = pytest.mark.unit

NAME = "TEST_POSITIVE_INT"


def test_should_return_the_default_when_unset(monkeypatch):
    monkeypatch.delenv(NAME, raising=False)

    assert positive_int_env(NAME, 7) == 7


@pytest.mark.parametrize("blank", ["", "   ", "\t"])
def test_should_return_the_default_when_blank(monkeypatch, blank):
    monkeypatch.setenv(NAME, blank)

    assert positive_int_env(NAME, 7) == 7


@pytest.mark.parametrize(("raw_value", "expected"), [("1", 1), ("50051", 50051), (" 5 ", 5)])
def test_should_return_a_valid_integer_instead_of_the_default(monkeypatch, raw_value, expected):
    monkeypatch.setenv(NAME, raw_value)

    assert positive_int_env(NAME, 7) == expected


@pytest.mark.parametrize("raw_value", ["abc", "1.5", "5a"])
def test_should_raise_on_a_value_that_is_not_an_integer(monkeypatch, raw_value):
    monkeypatch.setenv(NAME, raw_value)

    with pytest.raises(ValueError, match=f"{NAME} must be an integer"):
        positive_int_env(NAME, 7)


@pytest.mark.parametrize("raw_value", ["0", "-1"])
def test_should_raise_on_zero_and_negative_values(monkeypatch, raw_value):
    monkeypatch.setenv(NAME, raw_value)

    with pytest.raises(ValueError, match=f"{NAME} must be positive"):
        positive_int_env(NAME, 7)
