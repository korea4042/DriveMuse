package ai.drivemuse.app.onboarding

import ai.drivemuse.domain.*
import androidx.compose.foundation.layout.*
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
                var text by remember(q.id,a.freeText) { mutableStateOf(a.freeText) }
                OutlinedTextField(value=text,onValueChange={text=it.take(240)},label={Text("곡 또는 아티스트 (선택)")},modifier=Modifier.fillMaxWidth())
                Button(onClick={answer(a.copy(freeText=text,status=AnswerStatus.ANSWERED))},enabled=!busy) {Text("답변 저장")}
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
