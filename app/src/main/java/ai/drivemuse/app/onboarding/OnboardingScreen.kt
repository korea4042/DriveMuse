package ai.drivemuse.app.onboarding

import ai.drivemuse.domain.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun OnboardingScreen(d: SurveyDraft, busy: Boolean, answer: (SurveyAnswer)->Unit, move: (Int)->Unit, consent: (Boolean)->Unit, complete: (Boolean)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("DriveMuse · 나의 첫 드라이브",style=MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress={d.step.toFloat()/Survey.questions.size},modifier=Modifier.fillMaxWidth())
        if(d.step<Survey.questions.size) {
            val q=Survey.questions[d.step];val a=d.answers.find { it.question.id==q.id }?:SurveyAnswer(q)
            Text("${d.step+1} / ${Survey.questions.size}")
            Text(q.text,style=MaterialTheme.typography.headlineMedium)
            Text(if(q.intent==Intent.EXCLUSION) "직접 선택한 장르만 제외합니다." else "선택하지 않은 답은 싫어함으로 처리하지 않아요.")
            q.options.forEach { c ->
                FilterChip(selected=a.status==AnswerStatus.ANSWERED && c.id in a.selected,onClick={
                    val base=if(a.status==AnswerStatus.ANSWERED) a.selected else emptySet()
                    val selected=if(!q.multiple) setOf(c.id) else if(c.id in base) base-c.id else base+c.id
                    answer(a.copy(selected=selected,status=AnswerStatus.ANSWERED))
                },enabled=!busy,label={Text(c.label)},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp))
            }
            if(q.options.isEmpty()) {
                // One name at a time. The old single field asked for "곡 또는 아티스트", so people
                // typed a comma-separated list, and the whole string went to search as one query —
                // "에스파,카리나,엔믹스" matches nothing. Splitting on commas would only guess, and
                // guessing is wrong for the many names that contain a comma or a space. Adding them
                // one by one means the app never has to guess, and the user can see what was kept.
                val entries = remember(a.freeText) { a.freeText.split('\n').map { it.trim() }.filter { it.isNotBlank() } }
                var draft by remember(q.id) { mutableStateOf("") }
                fun commit(list: List<String>) = answer(a.copy(freeText=list.joinToString("\n").take(240),status=if(list.isEmpty()) AnswerStatus.SKIPPED else AnswerStatus.ANSWERED))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(
                        value=draft,onValueChange={ draft=it.take(60) },
                        label={Text("곡 또는 아티스트")},
                        placeholder={Text("예: 에스파")},
                        singleLine=true,
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
                        keyboardActions=KeyboardActions(onDone={ if(draft.isNotBlank()) { commit(entries+draft.trim()); draft="" } }),
                        modifier=Modifier.weight(1f)
                    )
                    Button(onClick={ if(draft.isNotBlank()) { commit(entries+draft.trim()); draft="" } },
                           enabled=!busy && draft.isNotBlank(),
                           modifier=Modifier.heightIn(min=56.dp)) {Text("추가")}
                }
                if(entries.isEmpty()) Text("아직 추가한 이름이 없어요. 비워 두셔도 됩니다.",style=MaterialTheme.typography.bodySmall)
                else {
                    Text("추가한 ${entries.size}개 · 각각 따로 검색합니다",style=MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        entries.forEach { name ->
                            InputChip(selected=true,onClick={ commit(entries-name) },
                                label={Text(name)},
                                trailingIcon={ Icon(Icons.Outlined.Close,"$name 삭제",Modifier.size(18.dp)) },
                                modifier=Modifier.heightIn(min=48.dp))
                        }
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(AnswerStatus.UNKNOWN to "잘 모르겠어요",AnswerStatus.ANY to "상관없어요",AnswerStatus.NONE to "없어요").forEach { (status,label) -> TextButton(onClick={answer(a.copy(status=status))},enabled=!busy) {Text(label)} }
            }
            Text("답변: ${when(a.status) { AnswerStatus.ANSWERED->"선택 완료";AnswerStatus.UNKNOWN->"미확인";AnswerStatus.SKIPPED->"건너뜀";AnswerStatus.ANY->"상관없음";AnswerStatus.NONE->"없음" }}",style=MaterialTheme.typography.bodySmall)
            Button(onClick={move(d.step+1)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("다음")}
            TextButton(onClick={answer(a.copy(status=AnswerStatus.SKIPPED));move(d.step+1)},enabled=!busy) {Text("건너뛰기")}
        } else {
            val p=Survey.map(d.answers)
            Text("이 취향으로 시작할게요",style=MaterialTheme.typography.headlineMedium)
            Text("선호 ${p.preferences.size}개 · 제외 ${p.exclusions.size}개 · 새로운 곡 ${(p.discovery*100).toInt()}%")
            Text("미응답은 중립으로 유지합니다. 첫 분위기는 첫 세션에만 적용해요. 나중에 에이전트에서 수정할 수 있습니다.")
            Row { Checkbox(checked=d.aiConsent,onCheckedChange=consent,enabled=!busy);Text("Gemini 추천 사용 (선택)\n설문 답변, 후보 곡과 요약된 반응을 Google에 전송합니다. 좌표와 계정 토큰은 보내지 않아요.") }
            Button(onClick={complete(false)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("완료하고 시작")}
            TextButton(onClick={complete(true)},enabled=!busy) {Text("샘플로 둘러보기")}
        }
        if(d.step>0) TextButton(onClick={move(d.step-1)},enabled=!busy) {Text("이전")}
    }
}
