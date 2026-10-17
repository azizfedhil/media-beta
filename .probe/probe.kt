import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

fun test(scope: kotlinx.coroutines.CoroutineScope) {
    // mimics AwaitPointerEventScope receiver: a CoroutineScope context
    scope.launch(start = CoroutineStart.UNDISPATCHED) { println("hi") }
}
