package link.infra.packwiz.installer.metadata.curseforge

import cc.ekblad.toml.tomlMapper
import com.google.gson.annotations.SerializedName

data class CurseForgeUpdateData(
	@SerializedName("fileId")
	val fileId: Int,

	@SerializedName("projectId")
	val projectId: Int,
): UpdateData {
	companion object {
		fun mapper() = tomlMapper {
			mapping<CurseForgeUpdateData>("file-id" to "fileId", "project-id" to "projectId")
		}
	}
}