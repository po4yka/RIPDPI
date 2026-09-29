"""Check Android accessibility state readers without an Appium server."""

from unittest.mock import Mock

import pytest

from pages.base_page import BasePage


@pytest.mark.parametrize("value, expected", [("true", True), ("false", False)])
def test_checked_state(value, expected):
    page = BasePage(Mock())
    page.wait_for = Mock()
    page.wait_for.return_value.get_attribute.side_effect = {"checkable": "true", "checked": value}.get
    assert page.is_checked("switch") is expected, "Checked state should match the Android value"


@pytest.mark.parametrize("value", [None, "", "unknown"])
def test_missing_checked_state_is_rejected(value):
    page = BasePage(Mock())
    page.wait_for = Mock()
    page.wait_for.return_value.get_attribute.side_effect = {"checkable": "true", "checked": value}.get
    with pytest.raises(ValueError, match="Invalid checked state"):
        page.is_checked("switch")


@pytest.mark.parametrize("checkable, attribute", [("true", "checked"), ("false", "selected")])
@pytest.mark.parametrize("value, expected", [("true", True), ("false", False)])
def test_selection_state(checkable, attribute, value, expected):
    page = BasePage(Mock())
    page.wait_for = Mock()
    page.wait_for.return_value.get_attribute.side_effect = {"checkable": checkable, attribute: value}.get
    assert page.is_selected("chip") is expected, "Selection should match the exposed Android state"


@pytest.mark.parametrize("checkable", ["true", "false"])
def test_missing_selection_state_is_rejected(checkable):
    page = BasePage(Mock())
    page.wait_for = Mock()
    page.wait_for.return_value.get_attribute.side_effect = {"checkable": checkable}.get
    with pytest.raises(ValueError, match="Invalid .* state"):
        page.is_selected("chip")


@pytest.mark.parametrize("checkable", [None, "false"])
def test_uncheckable_element_is_rejected(checkable):
    page = BasePage(Mock())
    page.wait_for = Mock()
    page.wait_for.return_value.get_attribute.side_effect = {"checkable": checkable, "checked": "false"}.get
    with pytest.raises(ValueError, match="does not expose a checked state"):
        page.is_checked("switch")


def test_missing_checkable_state_is_rejected():
    page = BasePage(Mock())
    page.wait_for = Mock()
    page.wait_for.return_value.get_attribute.side_effect = {"selected": "false"}.get
    with pytest.raises(ValueError, match="Invalid checkable state"):
        page.is_selected("chip")
