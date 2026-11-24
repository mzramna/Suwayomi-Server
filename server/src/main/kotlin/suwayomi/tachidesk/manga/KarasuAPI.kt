package suwayomi.tachidesk.manga

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.apibuilder.ApiBuilder.delete
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.head
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.apibuilder.ApiBuilder.put
import suwayomi.tachidesk.manga.controller.KarasuController
import suwayomi.tachidesk.manga.controller.KarasuShelfController

/** Routes under /api/v1/karasu. Contract: docs/karasu-integration.md */
object KarasuAPI {
    fun defineEndpoints() {
        path("karasu") {
            get("health", KarasuController::health)

            get("chapter/lookup", KarasuController::chapterLookup)
            get("chapter/{chapterId}/archive", KarasuController::archive)
            head("chapter/{chapterId}/archive", KarasuController::archive)
            get("manga/lookup", KarasuController::mangaLookup)

            post("backups/upload", KarasuController::backupUpload)
            get("backups", KarasuController::backupList)
            get("backups/{id}/download", KarasuController::backupDownload)
            delete("backups/{id}", KarasuController::backupDelete)

            get("merged", KarasuController::mergedAll)
            put("merged", KarasuController::mergedPutByRef)
            get("manga/{mangaId}/merged", KarasuController::mergedGet)
            put("manga/{mangaId}/merged", KarasuController::mergedPut)

            get("policy", KarasuController::policyGet)
            put("policy", KarasuController::policyPut)

            put("history", KarasuController::historyPut)

            post("operations", KarasuController::operationsPost)
            get("operations", KarasuController::operationsGet)

            post("deletions", KarasuController::deletionsPost)
            get("deletions", KarasuController::deletionsGet)

            // Shelf do Yatagarasu (KOReader): o health do plugin é a rota "health" acima
            get("shelf", KarasuShelfController::list)
            post("shelf", KarasuShelfController::upload)
            delete("shelf/{chapterId}", KarasuShelfController::delete)
            get("shelf/{chapterId}/file", KarasuShelfController::file)
            head("shelf/{chapterId}/file", KarasuShelfController::file)
            put("shelf/{chapterId}/read", KarasuShelfController::setRead)
        }
    }
}
