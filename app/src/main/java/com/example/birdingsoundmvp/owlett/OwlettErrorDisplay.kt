package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

data class OwlettErrorDisplay(val message: String, val diagnostics: String? = null) {
    companion object {
        fun from(raw: String): OwlettErrorDisplay {
            if (raw.contains("[诊断]")) return OwlettErrorDisplay(raw.substringBefore(AppText.get("\n\n[诊断]")),
                raw.substringAfter("[诊断]").trim().take(600))
            if (raw.contains("NoTransformationFoundException") || raw.contains("Expected response body")) {
                return OwlettErrorDisplay(AppText.get("请求未能完成，请重试。"),
                    AppText.get("旧版流式错误，响应格式不受支持。请使用当前版本重试。"))
            }
            return OwlettErrorDisplay(AppText.get(raw))
        }
    }
}
