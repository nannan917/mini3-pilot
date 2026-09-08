package com.skypatrol.groundstation

object CameraFeedback {
    fun storage(value: String): String = when (value) {
        "NORMAL" -> "正常"
        "NOT_INSERTED" -> "未插入，请在飞机上插入 microSD 卡"
        "FULL", "NO_REMAINING_FILE_INDICES" -> "已满，请备份文件后释放空间"
        "READ_ONLY" -> "只读，无法保存照片或录像"
        "FORMAT_NEEDED", "INVALID_FILE_SYSTEM" -> "文件系统异常，请先备份并在 DJI Fly 中检查"
        "BUSY", "FORMATTING", "INITIALIZING", "RECOVERING_FILES" -> "正在处理，请稍候"
        "SLOW", "WRITING_SLOWLY" -> "写入速度不足"
        "UNKNOWN" -> "等待飞机返回状态"
        else -> value
    }

    fun error(detail: String): String {
        val upper = detail.uppercase()
        return when {
            "SD_CARD_NOT_INSERTED" in upper || "SDCARD_NOT_INSERTED" in upper -> "飞机未插入 SD 卡，无法保存照片或录像。"
            "SD_CARD_FULL" in upper || "SDCARD_FULL" in upper -> "飞机 SD 卡已满，请备份后释放空间。"
            "DISCONNECTED" in upper -> "连接已断开，请检查遥控器和数据线。"
            "TIMEOUT" in upper || "TIME_OUT" in upper -> "飞机未及时确认指令，请查看飞机状态和连接诊断。"
            "UNSUPPORTED" in upper || "NOT_SUPPORTED" in upper -> "飞机不支持当前相机操作，详情已保存到连接诊断。"
            "ErrorImp" in detail -> "指令未成功，详情已保存。请打开「连接诊断」查看 SD 卡状态和错误信息。"
            else -> detail
        }
    }
}
