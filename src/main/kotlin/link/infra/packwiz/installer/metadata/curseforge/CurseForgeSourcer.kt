package link.infra.packwiz.installer.metadata.curseforge

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import okio.ByteString.Companion.decodeBase64
import kotlin.io.path.absolute
import com.google.gson.annotations.SerializedName

private class GetFilesRequest(val fileIds: List<Int>)
private class GetModsRequest(val modIds: List<Int>)

private class GetFilesResponse {
	class CfFile {
		@SerializedName("id")
		var id = 0
		@SerializedName("modId")
		var modId = 0
		@SerializedName("downloadUrl")
		var downloadUrl: String? = null
	}
	@SerializedName("data")
	val data = mutableListOf<CfFile>()
}

private class GetModsResponse {
	class CfMod {
		@SerializedName("id")
		var id = 0
		@SerializedName("name")
		var name = ""
		@SerializedName("links")
		var links: CfLinks? = null
	}
	class CfLinks {
		@SerializedName("websiteUrl")
		var websiteUrl = ""
	}
	@SerializedName("data")
	val data = mutableListOf<CfMod>()
}

private const val APIServer = "api.curseforge.com"

@Throws(JsonSyntaxException::class, JsonIOException::class)
fun resolveCfMetadata(mods: List<IndexFile.File>, packFolder: PackwizFilePath, clientHolder: ClientHolder): List<ExceptionDetails> {
	val failures = mutableListOf<ExceptionDetails>()
	val fileIdMap = mutableMapOf<Int, List<IndexFile.File>>()

	for (mod in mods) {
		if (!mod.linkedFile!!.update.contains("curseforge")) {
			failures.add(ExceptionDetails(mod.linkedFile!!.name, Exception("Failed to resolve CurseForge metadata: no CurseForge update section")))
			continue
		}
		val fileId = (mod.linkedFile!!.update["curseforge"] as CurseForgeUpdateData).fileId
		fileIdMap[fileId] = (fileIdMap[fileId] ?: listOf()) + mod
	}

	val reqData = GetFilesRequest(fileIdMap.keys.toList())
	val req = Request.Builder()
		.url("https://${APIServer}/v1/mods/files")
		.header("Accept", "application/json")
		.header("User-Agent", "packwiz-installer")
		.header("X-API-Key", BuildConfig.API_KEY_BASE64.decodeBase64()!!.toString())
		.post(Gson().toJson(reqData, GetFilesRequest::class.java).toRequestBody("application/json".toMediaType()))
		.build()
	val res = clientHolder.okHttpClient.newCall(req).execute()
	if (!res.isSuccessful || res.body == null) {
		res.closeQuietly()
		failures.add(ExceptionDetails("Other", Exception("Failed to resolve CurseForge metadata for file data: error code ${res.code}")))
		return failures
	}

	val resData = Gson().fromJson(res.body!!.charStream(), GetFilesResponse::class.java)
	res.closeQuietly()

	val manualDownloadMods = mutableMapOf<Int, List<Int>>()
	for (file in resData.data) {
		if (!fileIdMap.contains(file.id)) {
			failures.add(ExceptionDetails(file.id.toString(),
				Exception("Failed to find file from result: ID ${file.id}, Project ID ${file.modId}")))
			continue
		}
		if (file.downloadUrl == null) {
			manualDownloadMods[file.modId] = (manualDownloadMods[file.modId] ?: listOf()) + file.id
			continue
		}
		try {
			for (indexFile in fileIdMap[file.id]!!) {
				indexFile.linkedFile!!.resolvedUpdateData["curseforge"] =
					HttpUrlPath(file.downloadUrl!!.toHttpUrl())
			}
		} catch (e: IllegalArgumentException) {
			failures.add(ExceptionDetails(file.id.toString(),
				Exception("Failed to parse URL: ${file.downloadUrl} for ID ${file.id}, Project ID ${file.modId}", e)))
		}
	}

	// Some file types don't show up in the API at all! (e.g. shaderpacks)
	// Add unresolved files to manualDownloadMods
	for ((fileId, indexFiles) in fileIdMap) {
		for (file in indexFiles) {
			if (file.linkedFile != null) {
				if (file.linkedFile!!.resolvedUpdateData["curseforge"] == null) {
					val projectId = (file.linkedFile!!.update["curseforge"] as CurseForgeUpdateData).projectId
					manualDownloadMods[projectId] = (manualDownloadMods[projectId] ?: listOf()) + fileId
				}
			}
		}
	}

	if (manualDownloadMods.isNotEmpty()) {
		val reqModsData = GetModsRequest(manualDownloadMods.keys.toList())
		val reqMods = Request.Builder()
			.url("https://${APIServer}/v1/mods")
			.header("Accept", "application/json")
			.header("User-Agent", "packwiz-installer")
			.header("X-API-Key", BuildConfig.API_KEY_BASE64.decodeBase64()!!.toString())
			.post(Gson().toJson(reqModsData, GetModsRequest::class.java).toRequestBody("application/json".toMediaType()))
			.build()
		val resMods = clientHolder.okHttpClient.newCall(reqMods).execute()
		if (!resMods.isSuccessful || resMods.body == null) {
			resMods.closeQuietly()
			failures.add(ExceptionDetails("Other", Exception("Failed to resolve CurseForge metadata for mod data: error code ${resMods.code}")))
			return failures
		}

		val resModsData = Gson().fromJson(resMods.body!!.charStream(), GetModsResponse::class.java)
		resMods.closeQuietly()

		for (mod in resModsData.data) {
			if (!manualDownloadMods.contains(mod.id)) {
				failures.add(ExceptionDetails(mod.name,
					Exception("Failed to find project from result: ID ${mod.id}")))
				continue
			}

			for (fileId in manualDownloadMods[mod.id]!!) {
				if (!fileIdMap.contains(fileId)) {
					failures.add(ExceptionDetails(mod.name,
						Exception("Failed to find file from result: file ID $fileId")))
					continue
				}

				for (indexFile in fileIdMap[fileId]!!) {
					var modUrl = "${mod.links?.websiteUrl}/files/${fileId}"
					failures.add(ExceptionDetails(indexFile.name, Exception("This mod is excluded from the CurseForge API and must be downloaded manually.\n" +
						"Please go to ${modUrl} and save this file to ${indexFile.destURI.rebase(packFolder).nioPath.absolute()}"), modUrl))
				}
			}
		}
	}

	return failures
}