import com.voxbridge.runtime.ModelPaths
import com.voxbridge.runtime.ModelProfile
import java.io.File
fun main(args:Array<String>) {
 for(id in listOf("vi-en-qnn-envit5","vi-en-qnn-vinai")) {
  val paths=ModelPaths(File(args[0]),ModelProfile.fromId(id))
  val missing=paths.missing()
  check(missing.isEmpty()){id+": "+missing.joinToString("\n")}
  check(paths.componentStatus().all{it.ready})
  println("MODEL_CONTRACT_VERIFIED $id")
 }
}
