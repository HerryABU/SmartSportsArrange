"""sports-ai：运动会编排系统的 AI 训练侧（Python 3.12）。

生产部署**不依赖本包**：本包只负责「合成数据 → 训练 → 导出 ONNX」。
导出后的 .onnx 由 Java 端 ``com.sports.schedule.ai`` 通过 onnxruntime 加载推理。

模块划分（对应架构文档）：
- ``data``        ：合成报名数据生成器 + 实例特征提取（兼项共现统计层）
- ``models``      ：算法选择器（MLP）+ 冲突簇 GNN
- ``train_*``     ：训练入口
- ``export_onnx`` ：统一导出 ONNX（固定输入输出契约，见 README）
"""

__version__ = "0.1.0"
