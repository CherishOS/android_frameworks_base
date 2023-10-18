package com.android.systemui.user.domain.interactor

import android.content.pm.UserInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.user.data.repository.FakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class SelectedUserInteractorTest : SysuiTestCase() {

    private lateinit var underTest: SelectedUserInteractor

    private val userRepository = FakeUserRepository()

    @Before
    fun setUp() {
        userRepository.setUserInfos(USER_INFOS)
        underTest =
            SelectedUserInteractor(
                userRepository,
                FakeFeatureFlagsClassic().apply { set(Flags.REFACTOR_GETCURRENTUSER, true) }
            )
    }

    @Test
    fun getSelectedUserIdReturnsId() {
        runBlocking { userRepository.setSelectedUserInfo(USER_INFOS[0]) }

        val actualId = underTest.getSelectedUserId()

        assertThat(actualId).isEqualTo(USER_INFOS[0].id)
    }

    companion object {
        private val USER_INFOS =
            listOf(
                UserInfo(100, "First user", 0),
                UserInfo(101, "Second user", 0),
            )
    }
}
