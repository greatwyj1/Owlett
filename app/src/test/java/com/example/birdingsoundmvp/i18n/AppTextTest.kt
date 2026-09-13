package com.example.birdingsoundmvp.i18n

import org.junit.Assert.*
import org.junit.Test

class AppTextTest {
    @Test fun chineseResourcesAreAvailableToPureKotlinAndDoNotInterpolateUserContentAgain() {
        assertEquals("重试", AppText.get("Retry"))
        assertEquals("模型：my {1}", AppText.format("Model: {0}", "my {1}"))
        assertEquals("观鸟计划", AppText.get("Plan"))
    }
}
