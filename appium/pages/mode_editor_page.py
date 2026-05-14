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
        self.scroll_to(self.PROXY_IP)
        self.clear_and_type(self.PROXY_IP, ip)
        self.scroll_to(self.PROXY_PORT)
        self.clear_and_type(self.PROXY_PORT, port)

    def set_max_connections(self, value: str) -> None:
        self.scroll_down_to(self.MAX_CONNECTIONS, max_swipes=14)
        self.clear_and_type(self.MAX_CONNECTIONS, value)

    def set_buffer_size(self, value: str) -> None:
        self.scroll_down_to(self.BUFFER_SIZE, max_swipes=14)
        self.clear_and_type(self.BUFFER_SIZE, value)

    def set_default_ttl(self, value: str) -> None:
        self.scroll_down_to(self.DEFAULT_TTL, max_swipes=14)
        self.clear_and_type(self.DEFAULT_TTL, value)

    def toggle_command_line(self) -> None:
        self.scroll_down_to(self.CMD_TOGGLE, max_swipes=14)
        self.tap(self.CMD_TOGGLE)

    def is_command_line_args_visible(self) -> bool:
        self.scroll_down_to(self.CMD_ARGS, max_swipes=14)
        return self.is_visible(self.CMD_ARGS)

    def tap_save(self) -> None:
        self.tap(self.SAVE)

    def tap_cancel(self) -> None:
        self.tap(self.CANCEL)

    def is_validation_error_visible(self) -> bool:
        return self.is_visible(self.VALIDATION_SNACKBAR)
