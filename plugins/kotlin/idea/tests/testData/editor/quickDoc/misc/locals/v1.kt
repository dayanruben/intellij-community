fun variableContainerA() {
    val <caret>v1: String
    var v2: AuxFaceA
    @AuxAnnA val v3: AuxClassA
    var (v4, v5) = AuxDataClassA(0, "")
    val v6 = 1
    var v7 = object : AuxFaceA {}
    var v8: AuxFaceA = object : AuxFaceA {}
    lateinit var v9: AuxClassB
}
//INFO: <div class='definition'><pre><span style="color:#000080;font-weight:bold;">local</span> <span style="color:#000080;font-weight:bold;">val</span> <span style="color:#000000;">v1</span><span style="">: </span><span style="color:#000000;"><a href="psi_element://kotlin.String">String</a></span></pre></div>
