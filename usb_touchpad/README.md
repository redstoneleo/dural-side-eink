USB Touchpad example

This example turns the LilyGo EPD47 (ESP32-S3) into a USB HID touchscreen.
It reads the GT911 touch panel and reports single-finger press, move, and
release events as absolute touch coordinates. Android should treat it like an
external touch display instead of a mouse wheel.

Notes:
- The default PlatformIO configuration disables USB CDC on boot so Android sees
  a simpler HID-only device. Use BOOT + RESET to enter download mode if the
  upload port does not appear automatically.
- Uses the ESP32 Arduino USB/TinyUSB HID support included with the platform.
- Plug the board into a computer USB port. Select the T5-ePaper-S3 environment and upload.

References:
- TinyUSB HID examples
- LilyGo touch examples
