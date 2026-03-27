"""VPN permission dialog page object."""

from __future__ import annotations

from .base_page import BasePage


class PermissionDialogPage(BasePage):
    DIALOG = "vpn-permission-dialog"
    CONTINUE = "vpn-permission-dialog-continue"
    DISMISS = "vpn-permission-dialog-dismiss"

    def is_dialog_visible(self) -> bool:
        return self.is_visible(self.DIALOG)

    def tap_continue(self) -> None:
        self.tap(self.CONTINUE)

    def tap_dismiss(self) -> None:
        self.tap(self.DISMISS)
