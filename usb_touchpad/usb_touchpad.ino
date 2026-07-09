// USB Composite Device: Touchpad + Image Receiver
// Single-Touch robust version with hardware Debounce & EPD rendering
// PC Script uses USBCDC for image transfer

#ifndef BOARD_HAS_PSRAM
#error "Please enable PSRAM! (PlatformIO: -DBOARD_HAS_PSRAM in build_flags)"
#endif

#include <Arduino.h>
#include <Wire.h>
#include "USB.h"
#include "USBCDC.h"
#include "USBHID.h"
#include <TouchDrvGT911.hpp>
#include "epd_driver.h"

// --- DYNAMIC CONFIGURATION (Controlled by Android App) ---
// 这些变量可以通过串口指令 CMD:ROT_L 等实时修改
volatile int   g_res_x       = 540;   
volatile int   g_res_y       = 960;   
volatile bool  g_swap_xy     = false; 
volatile bool  g_mirror_x    = false; 
volatile bool  g_mirror_y    = false; 
volatile float g_sensitivity = 1.0f; 

#define BOARD_SCL  (17)
#define BOARD_SDA  (18)
#define TOUCH_INT  (47)

// 内部宏：用于提取 16 位值的字节
#define LSB(x) ((x) & 0xFF)
#define MSB(x) (((x) >> 8) & 0xFF)

#define BOARD_SCL  (17)
#define BOARD_SDA  (18)
#define TOUCH_INT  (47)

// 内部宏：用于提取 16 位值的字节
#define LSB(x) ((x) & 0xFF)
#define MSB(x) (((x) >> 8) & 0xFF)

constexpr uint8_t HID_REPORT_ID_TOUCH = 7;
constexpr uint16_t HID_LOGICAL_MAX = 32767;

// --- HID TOUCHSCREEN CLASS ---
class USBHIDTouchscreen : public USBHIDDevice {
public:
  USBHIDTouchscreen() : hid() {}
  void begin();
  void touchReport(bool pressed, uint16_t x, uint16_t y);
  uint16_t _onGetDescriptor(uint8_t *dst) override;

private:
  USBHID hid;
  static const uint8_t report_descriptor[];
};

// Multi-Touch Standard Descriptor (Generic Dynamic Profile)
// Note: Physical units set to 0 to let Android App handle coordinate mapping 
// via the Virtual Display viewport.
const uint8_t USBHIDTouchscreen::report_descriptor[] = {
  0x05, 0x0D,                    // Usage Page (Digitizers)
  0x09, 0x04,                    // Usage (Touch Screen)
  0xA1, 0x01,                    // Collection (Application)
  0x85, HID_REPORT_ID_TOUCH,     //   Report ID
  0x09, 0x22,                    //   Usage (Finger)
  0xA1, 0x02,                    //   Collection (Logical)
  0x09, 0x42,                    //     Usage (Tip Switch)
  0x09, 0x32,                    //     Usage (In Range)
  0x15, 0x00,                    //     Logical Minimum (0)
  0x25, 0x01,                    //     Logical Maximum (1)
  0x75, 0x01,                    //     Report Size (1)
  0x95, 0x02,                    //     Report Count (2)
  0x81, 0x02,                    //     Input (Data,Var,Abs)
  0x95, 0x06,                    //     Report Count (6) - Padding
  0x81, 0x03,                    //     Input (Const,Var,Abs)
  
  0x05, 0x01,                    //     Usage Page (Generic Desktop)
  0x09, 0x30,                    //     Usage (X)
  0x15, 0x00,                    //     Logical Minimum (0)
  0x26, LSB(HID_LOGICAL_MAX), MSB(HID_LOGICAL_MAX), // Logical Maximum (32767)
  0x35, 0x00,                    //     Physical Minimum (0)
  0x45, 0x00,                    //     Physical Maximum (0 -> Undefined)
  0x75, 0x10,                    //     Report Size (16)
  0x95, 0x01,                    //     Report Count (1)
  0x81, 0x02,                    //     Input (Data,Var,Abs)
  
  0x09, 0x31,                    //     Usage (Y)
  0x81, 0x02,                    //     Input (Data,Var,Abs)
  
  0x05, 0x0D,                    //     Usage Page (Digitizers)
  0x09, 0x51,                    //     Usage (Contact Identifier)
  0x75, 0x08,                    //     Report Size (8)
  0x95, 0x01,                    //     Report Count (1)
  0x15, 0x00,                    //     Logical Minimum (0)
  0x25, 0x20,                    //     Logical Maximum (32 contact IDs)
  0x81, 0x02,                    //     Input (Data,Var,Abs)
  
  0xC0,                          //   End Collection
  0x09, 0x54,                    //   Usage (Contact Count)
  0x15, 0x00,                    //   Logical Minimum (0)
  0x25, 0x01,                    //   Logical Maximum (1)
  0x75, 0x08,                    //   Report Size (8)
  0x95, 0x01,                    //   Report Count (1)
  0x81, 0x02,                    //     Input (Data,Var,Abs)
  0xC0                           // End Collection
};

void USBHIDTouchscreen::begin() {
  static bool initialized = false;
  if (!initialized) {
    initialized = true;
    hid.addDevice(this, sizeof(report_descriptor));
  }
  hid.begin();
}

void USBHIDTouchscreen::touchReport(bool pressed, uint16_t x, uint16_t y) {
  struct __attribute__((packed)) TouchReport {
    uint8_t flags;         // Bit 0: Tip Switch, Bit 1: In Range
    uint16_t x;
    uint16_t y;
    uint8_t contact_id;
    uint8_t contact_count;
  } report;

  // Apply Dynamic Sensitivity
  float scaled_x = (float)x * g_sensitivity;
  float scaled_y = (float)y * g_sensitivity;
  
  report.flags = pressed ? 0x03 : 0x00; 
  report.x = (uint16_t)constrain(scaled_x, 0, (float)HID_LOGICAL_MAX);
  report.y = (uint16_t)constrain(scaled_y, 0, (float)HID_LOGICAL_MAX);
  report.contact_id = 0;
  report.contact_count = pressed ? 1 : 0;

  hid.SendReport(HID_REPORT_ID_TOUCH, &report, sizeof(report));
}

uint16_t USBHIDTouchscreen::_onGetDescriptor(uint8_t *dst) {
  memcpy(dst, report_descriptor, sizeof(report_descriptor));
  return sizeof(report_descriptor);
}

// --- GLOBALS ---
USBCDC USBSerial;
USBHIDTouchscreen Touchscreen;
TouchDrvGT911 touch;

// 这里保留一个硬件串口用于调试（GPIO 43/44），不占用 USB 总线
#define HWSerial Serial 


static uint16_t mapTouchCoordinate(int16_t value, int16_t maximum) {
  value = constrain(value, 0, maximum - 1);
  return static_cast<uint16_t>((static_cast<uint32_t>(value) * HID_LOGICAL_MAX) / (maximum - 1));
}

// --- IMAGE RECEIVER PROTOCOL ---
#define MAGIC           "EIMG"
#define MAGIC_LEN       4
#define PROTOCOL        "chunk-ack-v4"
#define RX_CHUNK_SIZE   4096          // 接收缓冲区（与 Python WRITE_CHUNK_SIZE 对齐）
#define BYTE_TIMEOUT_MS 10000 

static uint8_t *framebuffer = nullptr;
static uint8_t *rx_frame_buffer = nullptr;
static uint8_t *raw_image_buffer = nullptr;

static constexpr uint32_t EPD_FRAME_BYTES = EPD_WIDTH * EPD_HEIGHT / 2;
static constexpr uint32_t LZ4_MAX_INPUT_BYTES = EPD_FRAME_BYTES + (EPD_FRAME_BYTES / 255) + 16;

bool usb_serial_read_bytes(uint8_t *buf, size_t n) {
    size_t received = 0;
    uint32_t last_byte_time = millis();
    while (received < n) {
        int available = USBSerial.available();
        if (available > 0) {
            size_t remaining = n - received;
            size_t to_read = (available < (int)remaining) ? (size_t)available : remaining;
            size_t got = USBSerial.read(buf + received, to_read);
            if (got > 0) {
                received += got;
                last_byte_time = millis();
            }
        } else if (millis() - last_byte_time > BYTE_TIMEOUT_MS) {
            return false;
        } else {
            vTaskDelay(pdMS_TO_TICKS(1)); // Yield to not trip watchdog
        }
    }
    return true;
}

void display_framebuffer() {
    epd_poweron();
    epd_clear();
    epd_draw_grayscale_image(epd_full_screen(), framebuffer);
    epd_poweroff();
}

int lz4_decompress_block(const uint8_t *src, uint32_t src_len, uint8_t *dst, uint32_t dst_len) {
    const uint8_t *ip = src;
    const uint8_t *src_end = src + src_len;
    uint8_t *op = dst;
    uint8_t *dst_end = dst + dst_len;

    while (ip < src_end) {
        uint8_t token = *ip++;

        uint32_t literal_len = token >> 4;
        if (literal_len == 15) {
            uint8_t s;
            do {
                if (ip >= src_end) return -1;
                s = *ip++;
                literal_len += s;
            } while (s == 255);
        }

        if ((uint32_t)(src_end - ip) < literal_len || (uint32_t)(dst_end - op) < literal_len) {
            return -2;
        }
        memcpy(op, ip, literal_len);
        ip += literal_len;
        op += literal_len;

        if (ip >= src_end) {
            break;
        }

        if ((uint32_t)(src_end - ip) < 2) return -3;
        uint32_t offset = (uint32_t)ip[0] | ((uint32_t)ip[1] << 8);
        ip += 2;
        if (offset == 0 || offset > (uint32_t)(op - dst)) return -4;

        uint32_t match_len = token & 0x0F;
        if (match_len == 15) {
            uint8_t s;
            do {
                if (ip >= src_end) return -5;
                s = *ip++;
                match_len += s;
            } while (s == 255);
        }
        match_len += 4;

        if ((uint32_t)(dst_end - op) < match_len) return -6;
        uint8_t *match = op - offset;
        while (match_len--) {
            *op++ = *match++;
        }
    }

    return op - dst;
}

void copy_packed_image_to_framebuffer(const uint8_t *src, uint16_t img_w, uint16_t img_h) {
    memset(framebuffer, 0xFF, EPD_FRAME_BYTES);

    bool full_screen = (img_w == EPD_WIDTH && img_h == EPD_HEIGHT);
    if (full_screen) {
        memcpy(framebuffer, src, EPD_FRAME_BYTES);
        return;
    }

    int32_t offset_x = (EPD_WIDTH  - img_w) / 2;
    int32_t offset_y = (EPD_HEIGHT - img_h) / 2;
    uint32_t row_bytes = img_w / 2;

    for (uint16_t row = 0; row < img_h; row++) {
        uint32_t src_offset = row * row_bytes;
        uint32_t fb_offset = ((offset_y + row) * EPD_WIDTH + offset_x) / 2;
        memcpy(framebuffer + fb_offset, src + src_offset, row_bytes);
    }
}

// --- TOUCH PARALLEL TASK ---
bool was_touching = false;
TaskHandle_t touch_task_handle = NULL;

void touch_task(void *pvParameters) {
  int16_t x, y;
  int empty_reads = 0;
  int16_t last_x = -1, last_y = -1;

  while (1) {
    if (touch.getPoint(&x, &y, 1)) {
      empty_reads = 0;
      if (!was_touching || abs(x - last_x) > 1 || abs(y - last_y) > 1) {
        // Use current global resolution variables
        Touchscreen.touchReport(true, mapTouchCoordinate(x, g_res_x), mapTouchCoordinate(y, g_res_y));
      }
      last_x = x;
      last_y = y;
      was_touching = true;
    } else {
      if (was_touching) {
        empty_reads++;
        if (empty_reads > 3) { 
          Touchscreen.touchReport(false, mapTouchCoordinate(last_x, g_res_x), mapTouchCoordinate(last_y, g_res_y));
          was_touching = false;
          last_x = -1;
          last_y = -1;
        }
      }
    }
    vTaskDelay(pdMS_TO_TICKS(10)); // Polling ~100Hz
  }
}

void setup() {
  // 1. 【极速报到 + 申请电力】
  // 向手机申请 500mA 电力，防止海信/摩托罗拉因瞬时电流大而断电
  USB.usbPower(500); 
  
  // 初始化 USB 复合设备描述符
  Touchscreen.begin(); 
  USBSerial.setRxBufferSize(32768); 
  USBSerial.begin();   
  USB.begin(); 
  
  // 2. 硬件调试串口（物理引脚，非 USB）
  HWSerial.begin(115200); 
  HWSerial.println("USB Composite stack online. Initializing EPD...");

  // 3. 分配内存（耗时操作放在 USB 启动之后）
  framebuffer = (uint8_t *)ps_calloc(1, EPD_WIDTH * EPD_HEIGHT / 2);
  if (!framebuffer) {
      HWSerial.println("FATAL: PSRAM alloc failed!");
      while (true) { delay(1000); }
  }
  rx_frame_buffer = (uint8_t *)ps_malloc(LZ4_MAX_INPUT_BYTES);
  if (!rx_frame_buffer) {
      HWSerial.println("FATAL: PSRAM RX buffer alloc failed!");
      while (true) { delay(1000); }
  }
  raw_image_buffer = (uint8_t *)ps_malloc(EPD_FRAME_BYTES);
  if (!raw_image_buffer) {
      HWSerial.println("FATAL: PSRAM raw image buffer alloc failed!");
      while (true) { delay(1000); }
  }
  memset(framebuffer, 0xFF, EPD_FRAME_BYTES);

  // 初始化墨水屏
  epd_init();
  epd_poweron();
  epd_clear();
  epd_poweroff();

  // 4. 初始化触摸芯片 GT911
  Wire.begin(BOARD_SDA, BOARD_SCL);
  pinMode(TOUCH_INT, OUTPUT);
  digitalWrite(TOUCH_INT, HIGH);

  uint8_t touchAddress = 0;
  Wire.beginTransmission(0x14); if (Wire.endTransmission() == 0) touchAddress = 0x14;
  Wire.beginTransmission(0x5D); if (Wire.endTransmission() == 0) touchAddress = 0x5D;

  if (touchAddress == 0) {
    HWSerial.println("Failed to find GT911!");
    while (1) delay(1000);
  }

  touch.setPins(-1, TOUCH_INT);
  if (!touch.begin(Wire, touchAddress, BOARD_SDA, BOARD_SCL)) {
    HWSerial.println("Failed to init GT911!");
    while (1) delay(1000);
  }

  touch.setMaxCoordinates(g_res_x, g_res_y);
  touch.setSwapXY(g_swap_xy);
  touch.setMirrorXY(g_mirror_x, g_mirror_y);

  // 5. 启动并行任务
  xTaskCreateUniversal(touch_task, "touch", 4096, NULL, 5, &touch_task_handle, 0);

  // 6. 提频至 240MHz 准备高性能传输
  setCpuFrequencyMhz(240); 
  HWSerial.println("System Ready.");
}

// --- LOOP (Image Receiver logic) ---
void loop() {
    static int magic_matched = 0;
    if (!USBSerial.available()) {
        delay(5);
        return;
    }

    uint8_t c = (uint8_t)USBSerial.read();

    // --- DYNAMIC COMMAND PARSER (CMD:) ---
    static String cmd_buf = "";
    if (c == 'C') { cmd_buf = "C"; return; }
    if (cmd_buf.length() > 0) {
        cmd_buf += (char)c;
        if (c == '\n' || c == '\r') {
            if (cmd_buf.startsWith("CMD:ROT_P")) { 
              g_res_x=540; g_res_y=960; g_swap_xy=false; 
              touch.setSwapXY(false); touch.setMaxCoordinates(g_res_x, g_res_y);
              USBSerial.println("OK: PORTRAIT");
            }
            else if (cmd_buf.startsWith("CMD:ROT_L")) { 
              g_res_x=960; g_res_y=540; g_swap_xy=true; 
              touch.setSwapXY(true); touch.setMaxCoordinates(g_res_x, g_res_y);
              USBSerial.println("OK: LANDSCAPE");
            }
            else if (cmd_buf.startsWith("CMD:SCALE:")) { 
              g_sensitivity = cmd_buf.substring(10).toFloat(); 
              USBSerial.printf("OK: SENS=%f\n", g_sensitivity);
            }
            else if (cmd_buf.startsWith("CMD:MIRROR_X")) {
              g_mirror_x = !g_mirror_x;
              touch.setMirrorXY(g_mirror_x, g_mirror_y);
              USBSerial.printf("OK: MIRROR_X=%d\n", g_mirror_x);
            }
            else if (cmd_buf.startsWith("CMD:QUERY")) {
              // 自动反馈硬件信息，供 APP 识别适配
              USBSerial.println("INFO:RES=960x540,PHYS=103x58mm,NAME=EPD47-S3");
            }
            cmd_buf = "";
        }
        if (cmd_buf.length() > 32) cmd_buf = ""; 
        return;
    }

    if (c == (uint8_t)MAGIC[magic_matched]) {
        magic_matched++;
        if (magic_matched < MAGIC_LEN) return;
    } else {
        magic_matched = (c == (uint8_t)MAGIC[0]) ? 1 : 0;
        return;
    }
    magic_matched = 0;

    // --- SUSPEND TOUCH TASK ---
    // A vital protection! 
    // Suspending the touch task prevents Dual-Core race conditions 
    // inside the TinyUSB endpoint queues while transferring the giant 250KB image.
    if (touch_task_handle != NULL) {
        vTaskSuspend(touch_task_handle);
    }

    // header
    uint8_t header[8];
    if (!usb_serial_read_bytes(header, 8)) {
        USBSerial.println("ERR: header timeout");
        if (touch_task_handle != NULL) vTaskResume(touch_task_handle);
        return;
    }

    uint16_t img_w    = (uint16_t)header[0] | ((uint16_t)header[1] << 8);
    uint16_t img_h    = (uint16_t)header[2] | ((uint16_t)header[3] << 8);
    uint32_t data_len = (uint32_t)header[4] | ((uint32_t)header[5] << 8) | ((uint32_t)header[6] << 16) | ((uint32_t)header[7] << 24);

    uint32_t expected = (uint32_t)img_w * img_h / 2;
    bool is_raw_frame = (data_len == expected);
    if (img_w == 0 || img_w > EPD_WIDTH || (img_w & 1) != 0 ||
        img_h == 0 || img_h > EPD_HEIGHT ||
        data_len == 0 || data_len > LZ4_MAX_INPUT_BYTES) {
        USBSerial.printf("ERR: invalid size (w=%u h=%u len=%u)\n", img_w, img_h, data_len);
        if (touch_task_handle != NULL) vTaskResume(touch_task_handle);
        return;
    }

    uint32_t received_total = 0;

    USBSerial.printf("Protocol: %s\n", PROTOCOL);
    USBSerial.printf("Receiving: %dx%d, %u bytes, %s\n", img_w, img_h, data_len, is_raw_frame ? "raw" : "lz4");
    USBSerial.println("READY");

    while (received_total < data_len) {
        uint32_t remaining = data_len - received_total;
        uint32_t to_recv   = (remaining < RX_CHUNK_SIZE) ? remaining : RX_CHUNK_SIZE;

        if (!usb_serial_read_bytes(rx_frame_buffer + received_total, to_recv)) {
            USBSerial.printf("ERR: data timeout\n");
            if (touch_task_handle != NULL) vTaskResume(touch_task_handle);
            return;
        }

        // 【流水线核心】早回 ACK 
        // 收到块后立即回 A 触发旧 PC 脚本发下一包；Android 端会忽略这些 ACK。
        while (USBSerial.write('A') == 0) {
            vTaskDelay(pdMS_TO_TICKS(1));
        }

        received_total += to_recv;

        // Feed watchdog while draining the stream
        vTaskDelay(pdMS_TO_TICKS(1));
    }

    if (is_raw_frame) {
        copy_packed_image_to_framebuffer(rx_frame_buffer, img_w, img_h);
    } else if (img_w == EPD_WIDTH && img_h == EPD_HEIGHT) {
        int decoded = lz4_decompress_block(rx_frame_buffer, data_len, framebuffer, expected);
        if (decoded != (int)expected) {
            USBSerial.printf("ERR: lz4 decode failed (%d/%u)\n", decoded, expected);
            if (touch_task_handle != NULL) vTaskResume(touch_task_handle);
            return;
        }
    } else {
        int decoded = lz4_decompress_block(rx_frame_buffer, data_len, raw_image_buffer, expected);
        if (decoded != (int)expected) {
            USBSerial.printf("ERR: lz4 decode failed (%d/%u)\n", decoded, expected);
            if (touch_task_handle != NULL) vTaskResume(touch_task_handle);
            return;
        }
        copy_packed_image_to_framebuffer(raw_image_buffer, img_w, img_h);
    }

    USBSerial.println("Rendering...");
    display_framebuffer();
    USBSerial.println("OK");

    if (touch_task_handle != NULL) {
        vTaskResume(touch_task_handle);
    }
}
