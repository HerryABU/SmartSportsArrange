#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""增量一：把 9 个 Build 类方法从 ScheduleService 删除，调用点改 buildComponent.xxx，改写构造器。"""
import io

SVC = r"C:\Users\QBZ95\Desktop\tools\codes\sports\sports-backend\src\main\java\com\sports\service\ScheduleService.java"

with io.open(SVC, "r", encoding="utf-8", newline="") as f:
    text = f.read()

lines = text.split("\n")

# 1) 逐个方法：定位签名行 -> 括号配平找方法尾 -> 删除（含紧邻上方 javadoc 块）
SIGNATURES = [
    "private List<Registration> approvedRegs(Long eventId, String grade)",
    "private int countParticipants(Long eventId, String grade)",
    "private List<String> approvedGenders(Long eventId, String grade)",
    "private List<Unit> buildUnits(",
    "private List<Event> orderedEvents(",
    "private void estimateDurations(",
    "private List<Window> buildWindows(",
    "private Map<String, Object> mergeConfig(",
    "private Pool resolvePool(",
]

def find_method_end(sig):
    # 找签名行
    si = None
    for i, ln in enumerate(lines):
        if sig in ln:
            si = i
            break
    if si is None:
        return None, None
    # 找方法起始大括号
    depth = 0
    started = False
    end = si
    # 从签名行开始扫（整行可能含 '{'）
    i = si
    while i < len(lines):
        ln = lines[i]
        for ch in ln:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
                if started and depth == 0:
                    end = i
                    break
        if started and depth == 0:
            break
        i += 1
    # 向上包含紧邻的 javadoc 块（/** ... */）
    start = si
    j = si - 1
    while j >= 0:
        s = lines[j].strip()
        if s.startswith("*") or s == "/**" or s == "*/":
            start = j
            j -= 1
        else:
            break
    return start, end

# 收集待删区间（升序）
ranges = []
for sig in SIGNATURES:
    s, e = find_method_end(sig)
    if s is None:
        print("WARN not found:", sig)
        continue
    ranges.append((s, e))
    print("delete", sig, "->", s + 1, "..", e + 1)

# 逆序删除，避免偏移
ranges.sort(reverse=True)
for (s, e) in ranges:
    del lines[s:e + 1]

text2 = "\n".join(lines)

# 2) 调用点重命名（定义已删，剩余均为调用）
for name in ["approvedRegs", "countParticipants", "approvedGenders", "buildUnits",
            "orderedEvents", "estimateDurations", "buildWindows", "mergeConfig", "resolvePool"]:
    text2 = text2.replace(name + "(", "buildComponent." + name + "(")

# 3) 改写构造器：移除 @RequiredArgsConstructor，插入显式构造器（实例化 buildComponent）
CONSTRUCTOR = '''    public ScheduleService(EventScheduleRepository scheduleRepository,
                            EventRepository eventRepository,
                            RegistrationRepository registrationRepository,
                            ArrangementRepository arrangementRepository,
                            ArrangementService arrangementService,
                            SystemService systemService,
                            ConflictService conflictService,
                            VenueRepository venueRepository,
                            ScheduleOptimizer scheduleOptimizer,
                            ScheduleVerifier scheduleVerifier,
                            LowerBoundEstimator lowerBoundEstimator,
                            LnsImprover lnsImprover,
                            GeneticAlgorithm geneticAlgorithm,
                            ScheduleCollaborationService collaborationService,
                            RuleBasedScheduler ruleBasedScheduler,
                            AuditService auditService) {
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.arrangementRepository = arrangementRepository;
        this.arrangementService = arrangementService;
        this.systemService = systemService;
        this.conflictService = conflictService;
        this.venueRepository = venueRepository;
        this.scheduleOptimizer = scheduleOptimizer;
        this.scheduleVerifier = scheduleVerifier;
        this.lowerBoundEstimator = lowerBoundEstimator;
        this.lnsImprover = lnsImprover;
        this.geneticAlgorithm = geneticAlgorithm;
        this.collaborationService = collaborationService;
        this.ruleBasedScheduler = ruleBasedScheduler;
        this.auditService = auditService;
        this.buildComponent = new ScheduleBuildComponent(eventRepository, registrationRepository, systemService);
    }
'''
text2 = text2.replace("@RequiredArgsConstructor\n", "", 1)
# 在 @Transactional 之后插入构造器
text2 = text2.replace("@Transactional\npublic class ScheduleService {",
                       "@Transactional\npublic class ScheduleService {\n" + CONSTRUCTOR, 1)
# 添加 buildComponent 字段（放在现有 final 字段之后、@Value 之前较稳妥；此处简单追加到字段区尾）
text2 = text2.replace("    private final AuditService auditService;\n",
                       "    private final AuditService auditService;\n\n    private final ScheduleBuildComponent buildComponent;\n", 1)

with io.open(SVC, "w", encoding="utf-8", newline="") as f:
    f.write(text2)

remaining = len(text2.split("\n"))
print("remaining lines:", remaining)
print("buildComponent refs:", text2.count("buildComponent."))
