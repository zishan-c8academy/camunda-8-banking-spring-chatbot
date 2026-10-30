"""Small BPMN writer: nodes and flows in a table, simple orthogonal routing, diagram (DI) included.

Used by tools/generate_models.py. Element order inside each flow node follows the BPMN schema:
documentation, extensionElements, incoming, outgoing, then event definitions and loop characteristics.
"""
from xml.sax.saxutils import escape

SIZE = {"startEvent": (36, 36), "endEvent": (36, 36), "boundaryEvent": (36, 36),
        "intermediateCatchEvent": (36, 36), "intermediateThrowEvent": (36, 36),
        "exclusiveGateway": (50, 50), "parallelGateway": (50, 50), "eventBasedGateway": (50, 50)}
LABELLED = ("startEvent", "endEvent", "boundaryEvent", "intermediateCatchEvent", "exclusiveGateway",
            "parallelGateway", "eventBasedGateway")


def x(s):
    """Escape for an XML attribute."""
    return escape(s, {'"': "&quot;", "\n": "&#10;"})


class Model:
    def __init__(self, pid, name):
        self.pid, self.name = pid, name
        self.nodes = {}
        self.flows = []
        self.order = []
        self.process_ext = ""

    def node(self, nid, typ, name, x0, y0, w=None, h=None, body="", attrs="", parent=None, expanded=False, label=None):
        dw, dh = SIZE.get(typ, (110, 80))
        self.nodes[nid] = dict(type=typ, name=name, x=x0, y=y0, w=w or dw, h=h or dh, body=body, attrs=attrs,
                               parent=parent, expanded=expanded, label=label)
        self.order.append(nid)
        return nid

    def at(self, nid, side):
        n = self.nodes[nid]
        cx, cy = n["x"] + n["w"] / 2, n["y"] + n["h"] / 2
        return {"r": (n["x"] + n["w"], cy), "l": (n["x"], cy), "t": (cx, n["y"]), "b": (cx, n["y"] + n["h"])}[side]

    def flow(self, fid, src, tgt, name="", cond=None, via=None, out="r", inn="l", label=None):
        a, b = self.at(src, out), self.at(tgt, inn)
        if via is not None:
            pts = [a] + list(via) + [b]
        elif a[1] == b[1] or a[0] == b[0]:
            pts = [a, b]
        elif out in ("t", "b"):
            pts = [a, (a[0], b[1]), b]
        else:
            mx = (a[0] + b[0]) / 2
            pts = [a, (mx, a[1]), (mx, b[1]), b]
        self.flows.append((fid, src, tgt, name, cond, pts, label))

    def _flowrefs(self, nid):
        inc = "".join(f"<bpmn:incoming>{f[0]}</bpmn:incoming>" for f in self.flows if f[2] == nid)
        out = "".join(f"<bpmn:outgoing>{f[0]}</bpmn:outgoing>" for f in self.flows if f[1] == nid)
        return inc + out

    def _el(self, nid, indent="    "):
        n = self.nodes[nid]
        name = f' name="{x(n["name"])}"' if n["name"] else ""
        kids = ""
        if n["type"] in ("adHocSubProcess", "subProcess"):
            kids = "\n" + "\n".join(self._el(c, indent + "  ") for c in self.order if self.nodes[c]["parent"] == nid)
            kids += "".join(self._flow_xml(f, indent + "  ") for f in self.flows if self.nodes[f[1]]["parent"] == nid)
            kids += "\n" + indent
        body, tail = n["body"], ""
        cuts = [i for i in (body.find("EventDefinition "), body.find("EventDefinition>"),
                            body.find("<bpmn:multiInstanceLoopCharacteristics")) if i != -1]
        if cuts:
            cut = body.rfind("<bpmn:", 0, min(cuts) + len("<bpmn:"))
            body, tail = body[:cut], body[cut:]
        return (f'{indent}<bpmn:{n["type"]} id="{nid}"{name}{n["attrs"]}>{body}{self._flowrefs(nid)}{tail}'
                f'{kids}</bpmn:{n["type"]}>')

    def _flow_xml(self, f, indent="    "):
        fid, s, t, name, cond, _, _ = f
        nm = f' name="{x(name)}"' if name else ""
        c = f'<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">{escape(cond)}</bpmn:conditionExpression>' if cond else ""
        return f'\n{indent}<bpmn:sequenceFlow id="{fid}"{nm} sourceRef="{s}" targetRef="{t}">{c}</bpmn:sequenceFlow>'

    def xml(self, ns, defs_attrs, head=""):
        top = [nid for nid in self.order if self.nodes[nid]["parent"] is None]
        els = "\n".join(self._el(n) for n in top)
        fl = "".join(self._flow_xml(f) for f in self.flows if self.nodes[f[1]]["parent"] is None)
        shapes, edges = [], []
        for nid in self.order:
            n = self.nodes[nid]
            exp = ' isExpanded="true"' if n["expanded"] else ""
            lab = ""
            if n["type"] in LABELLED and n["name"]:
                lw = 100
                lx = n["x"] + n["w"] / 2 - lw / 2
                ly = n["y"] - 34 if n["type"].endswith("Gateway") else n["y"] + n["h"] + 4
                if n["label"]:
                    lx, ly = n["label"]
                lab = (f'<bpmndi:BPMNLabel><dc:Bounds x="{lx}" y="{ly}" width="{lw}" '
                       f'height="27" /></bpmndi:BPMNLabel>')
            shapes.append(f'<bpmndi:BPMNShape id="{nid}_di" bpmnElement="{nid}"{exp}><dc:Bounds x="{n["x"]}" '
                          f'y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" />{lab}</bpmndi:BPMNShape>')
        for fid, s, t, name, cond, pts, label in self.flows:
            wp = "".join(f'<di:waypoint x="{a}" y="{b}" />' for a, b in pts)
            lab = ""
            if name:
                if label:
                    lx, ly = label
                elif len(pts) > 2:
                    lx, ly = pts[1][0] + 6, pts[1][1] - 18
                else:
                    lx, ly = (pts[0][0] + pts[1][0]) / 2 - 20, pts[0][1] - 18
                lab = f'<bpmndi:BPMNLabel><dc:Bounds x="{lx}" y="{ly}" width="90" height="14" /></bpmndi:BPMNLabel>'
            edges.append(f'<bpmndi:BPMNEdge id="{fid}_di" bpmnElement="{fid}">{wp}{lab}</bpmndi:BPMNEdge>')
        nl = "\n      "
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" {ns} id="Defs_{self.pid}" targetNamespace="http://bpmn.io/schema/bpmn" {defs_attrs}>
{head}  <bpmn:process id="{self.pid}" name="{x(self.name)}" isExecutable="true">{self.process_ext}
{els}{fl}
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Diagram_{self.pid}">
    <bpmndi:BPMNPlane id="Plane_{self.pid}" bpmnElement="{self.pid}">
      {nl.join(shapes)}
      {nl.join(edges)}
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
'''


def dmn(dec_id, name, hit, inputs, outputs, rules, platform_version):
    ins = "".join(f'<input id="In_{i}" label="{x(lbl)}"><inputExpression id="InExpr_{i}" typeRef="{t}"><text>{escape(expr)}</text></inputExpression></input>'
                  for i, (lbl, expr, t) in enumerate(inputs))
    outs = "".join(f'<output id="Out_{i}" label="{x(lbl)}" name="{nm}" typeRef="{t}" />' for i, (lbl, nm, t) in enumerate(outputs))
    rs = []
    for r, (ie, oe, ann) in enumerate(rules):
        rs.append(f'\n      <rule id="R_{r}"><description>{escape(ann)}</description>' +
                  "".join(f'<inputEntry id="R_{r}_i{i}"><text>{escape(v)}</text></inputEntry>' for i, v in enumerate(ie)) +
                  "".join(f'<outputEntry id="R_{r}_o{i}"><text>{escape(v)}</text></outputEntry>' for i, v in enumerate(oe)) + "</rule>")
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" xmlns:dmndi="https://www.omg.org/spec/DMN/20191111/DMNDI/" xmlns:dc="http://www.omg.org/spec/DMN/20180521/DC/" xmlns:modeler="http://camunda.org/schema/modeler/1.0" id="Defs_{dec_id}" name="{x(name)}" namespace="http://camunda.org/schema/1.0/dmn" modeler:executionPlatform="Camunda Cloud" modeler:executionPlatformVersion="{platform_version}">
  <decision id="{dec_id}" name="{x(name)}">
    <decisionTable id="DT_{dec_id}" hitPolicy="{hit}">
      {ins}
      {outs}{"".join(rs)}
    </decisionTable>
  </decision>
  <dmndi:DMNDI><dmndi:DMNDiagram id="DD_{dec_id}"><dmndi:DMNShape id="DS_{dec_id}" dmnElementRef="{dec_id}"><dc:Bounds height="80" width="180" x="160" y="100" /></dmndi:DMNShape></dmndi:DMNDiagram></dmndi:DMNDI>
</definitions>
'''
