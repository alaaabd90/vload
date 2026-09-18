import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.utils.NetworkSelector
import io.nekohasekai.sagernet.utils.VloadNetworkController

fun main() {
    val events = mutableListOf<Pair<Int, Network?>>()
    val controller = VloadNetworkController { slot, network -> events.add(slot to network) }
    val cm = SagerNet.connectivity
    val a = Network(1)
    val b = Network(2)
    controller.start(0, NetworkSelector.WiFi)
    check(events == listOf(0 to null)) { "unacquired network was not marked unavailable" }
    val old = cm.callbacks.last()
    old.onAvailable(a)
    Handler.drain()
    check(controller.networkFor(0) == a)
    check(events.last() == 0 to a)

    val count = events.size
    old.onAvailable(a)
    old.onCapabilitiesChanged(a, NetworkCapabilities())
    Handler.drain()
    check(events.size == count) { "duplicate availability reset core health" }

    old.onAvailable(b)
    old.onLost(a)
    Handler.drain()
    check(controller.networkFor(0) == b) { "old network loss removed the replacement" }
    check(events.last() == 0 to b)

    old.onAvailable(a) // queued event from a request that will be replaced
    controller.start(0, NetworkSelector.WiFi)
    Handler.drain()
    check(controller.networkFor(0) == null) { "stale callback revived an old request" }
    check(events.last() == 0 to null)
    val current = cm.callbacks.last()
    old.onLost(b)
    current.onAvailable(b)
    Handler.drain()
    check(controller.networkFor(0) == b)

    current.onLost(b)
    Handler.drain()
    check(controller.networkFor(0) == null)
    current.onAvailable(b)
    controller.stopAll()
    val stoppedCount = events.size
    Handler.drain()
    check(events.size == stoppedCount) { "callback escaped stopAll" }
    check(controller.networkFor(0) == null)

    cm.failRegistration = true
    controller.start(1, NetworkSelector.Sim(1))
    check(events.last() == 1 to null) { "failed registration left slot available" }
    check(controller.networkFor(1) == null)
    println("PASS: acquisition, duplicate/capability updates, replacement, stale callbacks, loss, stop, registration failure")
}
