package ai.drivemuse.app.gemini

import android.content.Context
import ai.drivemuse.app.*
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

/** v2.3 §10: versions are pinned per role. common-guard stays 2.1; selector and metadata-interpreter moved to 2.3, discovery-planner is 2.2. */
enum class Role(val wire: String, val version: String) { SURVEY("taste-intake","2.1"), CONTEXT("context-interpreter","2.1"), REVIEW("listening-reviewer","2.1"), SELECTOR("selector","2.3"), METADATA("metadata-interpreter","2.3"), DISCOVERY("discovery-planner","2.2") }
object JsonGate {
    fun keys(j: JSONObject, vararg keys: String) { require(j.keys().asSequence().toSet()==keys.toSet()) }
    fun string(j: JSONObject, key: String, max: Int=160): String { val v=j.get(key);require(v is String && v.length<=max);return v }
    fun integer(j: JSONObject,key: String): Long { val v=j.get(key);require(v is Int || v is Long);return (v as Number).toLong() }
    fun number(j: JSONObject,key: String): Double { val v=j.get(key);require(v is Number && v.toDouble().isFinite());return v.toDouble() }
    fun strings(a: JSONArray,max: Int): List<String> { require(a.length()<=max);return (0 until a.length()).map { val v=a.get(it);require(v is String && v.length<=160);v } }
}
class RoleGateway(private val context: Context, private val dao: IntelligenceDao, private val configProvider: () -> Map<String, String> = { emptyMap() }) {
    private val quota=Mutex()
    /** §22–§23: the promoted in-app config wins; BuildConfig and google-services.json are defaults (T14). */
    private val config get() = configProvider()
    private val modelId get() = (config["modelId"] ?: "").ifBlank { BuildConfig.GEMINI_MODEL }
    private val firebaseApp get() = FirebaseRuntime.app(context, config)
    val configured get() = firebaseApp != null && modelId.isNotBlank()
    suspend fun call(role: Role, input: JSONObject, payloadSchema: Schema, validate: (JSONObject)->Unit): JSONObject = withTimeout(20000) {
        check(configured);val raw=input.toString();require(raw.length<=18000)
        val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) };val requestId=UUID.randomUUID().toString()
        quota.withLock {
            val now=System.currentTimeMillis();val day=now/86400000
            val old=dao.state("ai_quota");val count=if(old?.version==day) old.json.toInt() else 0
            check(count<60);dao.putState(IntelligenceState("ai_quota",(count+1).toString(),day,now))
        }
        val schema=Schema.obj(mapOf("role" to Schema.string(),"taskType" to Schema.string(),"promptVersion" to Schema.string(),"schemaVersion" to Schema.string(),"requestId" to Schema.string(),"inputHash" to Schema.string(),"payload" to payloadSchema))
        val system=context.assets.open("prompts/common-guard.v2.1.txt").bufferedReader().use { it.readText() }+"\n"+context.assets.open("prompts/${role.wire}.v${role.version}.txt").bufferedReader().use { it.readText() }
        val app=firebaseApp ?: error("Firebase 구성이 없습니다")
        val model=Firebase.ai(app=app, backend=GenerativeBackend.googleAI()).generativeModel(modelName=modelId,systemInstruction=content { text(system) },generationConfig=generationConfig { responseMimeType="application/json";responseSchema=schema;maxOutputTokens=1500;temperature=.2f })
        val request=JSONObject().put("role",role.wire).put("taskType",role.wire).put("promptVersion",role.version).put("schemaVersion",role.version).put("requestId",requestId).put("inputHash",hash).put("input",input)
        val text=model.generateContent(request.toString()).text?:error("Empty response");require(text.length<=16000)
        val response=JSONObject(text);JsonGate.keys(response,"role","taskType","promptVersion","schemaVersion","requestId","inputHash","payload")
        require(JsonGate.string(response,"role")==role.wire && JsonGate.string(response,"taskType")==role.wire && JsonGate.string(response,"promptVersion")==role.version && JsonGate.string(response,"schemaVersion")==role.version && JsonGate.string(response,"requestId")==requestId && JsonGate.string(response,"inputHash")==hash)
        response.getJSONObject("payload").also(validate)
    }
}
