/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.testrqes

import android.app.Application
import eu.europa.ec.rqesui.domain.entities.localization.LocalizableKey
import eu.europa.ec.rqesui.infrastructure.EudiRQESUi
import eu.europa.ec.rqesui.infrastructure.config.EudiRQESUiConfig
import java.net.URI

class TestRQESApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initRQESSDK()
    }

    private fun initRQESSDK() {
        EudiRQESUi.setup(this, DefaultConfig())
    }
}

private class DefaultConfig : EudiRQESUiConfig {

    override val qtsps: List<URI>
        get() = emptyList()

    override val translations: Map<String, Map<LocalizableKey, String>>
        get() = mapOf(
            "en" to mapOf(
                LocalizableKey.Mock to "Mock",
                LocalizableKey.MockWithValues to "Mock %@, %@"
            )
        )

    override val printLogs: Boolean
        get() = true

}