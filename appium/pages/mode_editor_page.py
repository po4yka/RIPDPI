"""Mode editor screen page object."""

from __future__ import annotations

from .base_page import BasePage


class ModeEditorPage(BasePage):
    SCREEN = "mode_editor-screen"
    PROXY_IP = "mode-editor-proxy-ip"
    PROXY_PORT = "mode-editor-proxy-port"
    MAX_CONNECTIONS = "mode-editor-max-connections"
    BUFFER_SIZE = "mode-editor-buffer-size"
    CHAIN_DSL = "mode-editor-chain-dsl"
    DEFAULT_TTL = "mode-editor-default-ttl"
    CMD_TOGGLE = "mode-editor-command-line-toggle"
    CMD_ARGS = "mode-editor-command-line-args"
    SAVE = "mode-editor-save"
    CANCEL = "mode-editor-cancel"
    VALIDATION_SNACKBAR = "mode-editor-validation-snackbar"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def fill_proxy(self, ip: str, port: str) -> None:
        self.clear_and_type(self.PROXY_IP, ip)
        self.clear_and_type(self.PROXY_PORT, port)

    def set_max_connections(self, value: str) -> None:
        self.clear_and_type(self.MAX_CONNECTIONS, value)

    def set_buffer_size(self, value: str) -> None:
        self.clear_and_type(self.BUFFER_SIZE, value)

    def set_default_ttl(self, value: str) -> None:
        self.scroll_to(self.DEFAULT_TTL)
        self.clear_and_type(self.DEFAULT_TTL, value)

    def toggle_command_line(self) -> None:
        self.scroll_to(self.CMD_TOGGLE)
        self.tap(self.CMD_TOGGLE)

    def tap_save(self) -> None:
        self.tap(self.SAVE)

    def tap_cancel(self) -> None:
        self.tap(self.CANCEL)

    def is_validation_error_visible(self) -> bool:
        return self.is_visible(self.VALIDATION_SNACKBAR)
