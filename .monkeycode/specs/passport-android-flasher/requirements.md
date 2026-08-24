# 需求文档：Passport 安卓固件刷写器

## Introduction

本项目为 FoloToy AI Passport（基于 ESP32-C3 的可穿戴 AI 硬件）开发一款原生安卓应用，用于通过 USB OTG 连接设备并刷写固件。应用功能对齐官方网页版刷写工具（https://ai-passport.folotoy.cn/tools/web-flasher/）：连接设备、识别芯片、选择本地固件、写入与校验、擦除设备数据、实时进度与日志。固件文件只在设备本地读取，不经过任何服务器。

## Glossary

- **AI Passport**：FoloToy 推出的可穿戴 AI 硬件，主控为 ESP32-C3。
- **USB-Serial/JTAG**：ESP32-C3 原生 USB 外设，通过 VID 0x303A / PID 0x1001 枚举为 USB 串口设备，ROM 内置 esptool 下载协议支持。
- **OTG**：On-The-Go，手机作为 USB Host 连接外设的工作模式。
- **固件镜像**：可写入芯片 Flash 的二进制文件，可为单个合并镜像（起始地址 0x0）或多个分区镜像（如 bootloader/partition-table/app）。
- **esptool 协议**：乐鑫 ESP 系列芯片的串口烧录协议，包含 SLIP 帧编码与 ROM/Stub 下载命令。
- **Stub loader**：由 ROM 引导加载的软件下载器，可加速烧写并提供擦除、读 Flash、MD5 校验等命令。
- **BSP**：Board Support Package，板级支持包。

## Requirements

### 需求 R1：USB 设备连接

**User Story:** 作为用户，我希望通过 USB OTG 线连接 AI Passport 并能获取设备访问权限，以便开始刷写。

#### Acceptance Criteria

1. WHEN 用户点击「连接设备」，系统 SHALL 请求 USB 设备访问权限并列出检测到的 AI Passport。
2. WHEN 手机检测到 VID=0x303A 且 PID=0x1001 的 USB 设备插入，系统 SHALL 提示用户授予访问权限。
3. WHEN 用户授予权限，系统 SHALL 打开设备并执行 esptool 连接流程（复位序列与同步握手）。
4. WHEN 连接成功，系统 SHALL 读取并显示芯片型号（ESP32-C3）、芯片修订版本、Flash 容量与 MAC 地址。
5. WHEN 连接失败，系统 SHALL 显示可读错误信息并允许用户重试。
6. WHEN 设备在刷写过程中被拔出，系统 SHALL 停止当前操作并提示连接丢失。

### 需求 R2：固件文件选择

**User Story:** 作为用户，我希望选择本地固件文件，以便写入设备。

#### Acceptance Criteria

1. WHEN 用户点击「选择固件」，系统 SHALL 允许通过系统文件选择器选择一个或多个 .bin 文件（最多 8 个）。
2. WHEN 用户选择单个合并固件，系统 SHALL 以起始地址 0x0 写入整个镜像。
3. WHEN 用户选择多个分区镜像，系统 SHALL 根据文件名推断默认起始地址并允许用户调整。
4. WHEN 固件文件大于可用 Flash 空间，系统 SHALL 拒绝写入并提示空间不足。
5. WHEN 未选择任何固件，系统 SHALL 禁用「开始写入」按钮。

### 需求 R3：固件写入

**User Story:** 作为用户，我希望将固件写入设备 Flash，并看到进度与结果。

#### Acceptance Criteria

1. WHEN 用户点击「开始写入」，系统 SHALL 依次执行固件写入流程：加载 stub loader、按分区写入数据、执行 MD5 校验。
2. WHILE 写入进行中，系统 SHALL 实时显示当前分区、写入字节数与总体百分比进度。
3. WHEN 每个分区写入完成，系统 SHALL 校验该分区的 MD5 哈希并报告是否一致。
4. WHEN 写入全部完成，系统 SHALL 重启设备并显示成功提示。
5. WHEN 任意分区写入失败，系统 SHALL 在重试 3 次后中止并报告失败原因。
6. WHEN 用户选择压缩写入，系统 SHALL 使用 deflate 压缩固件数据以减少传输量。

### 需求 R4：设备数据擦除

**User Story:** 作为用户，我希望擦除设备全部数据，以便恢复到出厂状态或解决异常。

#### Acceptance Criteria

1. WHEN 用户点击「清除设备数据」，系统 SHALL 先弹出二次确认对话框。
2. WHEN 用户确认，系统 SHALL 通过 stub loader 执行全片擦除并显示进度。
3. WHEN 擦除完成，系统 SHALL 显示成功提示。
4. WHEN 擦除失败，系统 SHALL 报告失败原因且不继续执行任何写入。

### 需求 R5：波特率设置

**User Story:** 作为用户，我希望选择通信波特率，以在速度与稳定性之间权衡。

#### Acceptance Criteria

1. WHEN 用户进入写入设置，系统 SHALL 提供 115200 / 230400 / 460800 / 921600 四档波特率选择。
2. WHEN 用户选择非默认波特率，系统 SHALL 在 stub 运行后通过 CHANGE_BAUDRATE 命令切换。
3. WHEN 波特率切换失败，系统 SHALL 回退到 115200 并提示用户。

### 需求 R6：日志与状态显示

**User Story:** 作为用户，我希望看到实时的设备日志，以便了解刷写过程中的每个步骤。

#### Acceptance Criteria

1. WHEN 连接或刷写过程中产生事件，系统 SHALL 将文本追加到日志区域，包含时间戳与操作描述。
2. WHEN 发生错误，系统 SHALL 将错误信息以明显样式显示在日志末尾。
3. WHEN 用户点击「清空日志」，系统 SHALL 清除日志区域内容。

### 需求 R7：安全与降级

**User Story:** 作为系统，我希望在不支持 OTG 或无权限的场景下安全降级，避免误操作。

#### Acceptance Criteria

1. WHEN 设备未连接，系统 SHALL 禁用写入与擦除按钮。
2. WHEN 手机不支持 USB Host，系统 SHALL 提示无法使用该功能。
3. WHEN 用户拒绝 USB 权限，系统 SHALL 停留在未连接状态并提示需要权限。
4. WHEN 刷写中断或失败，系统 SHALL 保证设备可再次进入下载模式重试，不产生不可恢复状态。
