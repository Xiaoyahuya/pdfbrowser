#!/usr/bin/env bash
set -euo pipefail

remote='gdrive:PDFBrowser资料库'
layout='/home/lfp/pdfbrowser/java-backend/deploy/library-layout'
set -a
source /home/lfp/pdfbrowser/java-backend/deploy/rclone-proxy.env
set +a

move() {
  local source_path="$1"
  local target_path="$2"
  printf 'MOVE %s -> %s\n' "$source_path" "$target_path"
  rclone moveto "$remote/$source_path" "$remote/$target_path"
}

root_dirs="$(rclone lsf "$remote" --dirs-only --max-depth 1)"
grep -Fxq '教程文档/' <<<"$root_dirs"
grep -Fxq '思考快与慢/' <<<"$root_dirs"
for target in '知识/' '书籍/' '科研/' '项目/' '学习/' '工程/' '归档/'; do
  if grep -Fxq "$target" <<<"$root_dirs"; then
    printf 'TARGET_ALREADY_EXISTS=%s\n' "$target" >&2
    exit 1
  fi
done

for category in 知识 书籍 科研 项目 学习 工程 归档; do
  rclone mkdir "$remote/$category"
done

# 旧入口先归档，避免迁移后留下指向旧编号目录的链接。
move '教程文档/README.md' '归档/旧索引/教程文档_README.md'
move '教程文档/00_知识体系/README.md' '归档/旧索引/知识体系_README.md'
move '教程文档/00_知识体系/深度剖析/README.md' '归档/旧索引/深度剖析_README.md'
move '教程文档/01_学习与训练/README.md' '归档/旧索引/学习与训练_README.md'
move '教程文档/01_学习与训练/学习计划/README.md' '归档/旧索引/学习计划_README.md'
move '教程文档/02_科研/README.md' '归档/旧索引/科研_README.md'
move '教程文档/03_系统与Linux/README.md' '归档/旧索引/系统与Linux_README.md'
move '教程文档/04_阅读资料/README.md' '归档/旧索引/阅读资料_README.md'
move '教程文档/05_工程记录/README.md' '归档/旧索引/工程记录_README.md'

# 知识：按技术域组织，每个域内部保留知识体系、深度剖析和代码课。
move '教程文档/00_知识体系/00_总索引与学习路线.md' '知识/总览/技术知识总索引与学习路线.md'
move '教程文档/00_知识体系/01_Vue3_知识体系.md' '知识/前端与浏览器/Vue3知识体系.md'
move '教程文档/00_知识体系/02_现代Java_知识体系.md' '知识/Java与JVM/现代Java知识体系.md'
move '教程文档/00_知识体系/03_Spring_知识体系.md' '知识/Java与JVM/Spring知识体系.md'
move '教程文档/00_知识体系/04_Go后端_知识体系.md' '知识/Go后端/Go后端知识体系.md'
move '教程文档/00_知识体系/05_Rust后端_知识体系.md' '知识/Rust后端/Rust后端知识体系.md'
move '教程文档/00_知识体系/Android-Java-Kotlin' '知识/移动开发/Android_Java_Kotlin'

move '教程文档/00_知识体系/深度剖析/01_Vue3运行时与响应式深度剖析.md' '知识/前端与浏览器/Vue3运行时与响应式深度剖析.md'
move '教程文档/00_知识体系/深度剖析/02_Spring容器事务与请求链路深度剖析.md' '知识/Java与JVM/Spring容器事务与请求链路深度剖析.md'
move '教程文档/00_知识体系/深度剖析/03_Go运行时并发与内存深度剖析.md' '知识/Go后端/Go运行时并发与内存深度剖析.md'
move '教程文档/00_知识体系/深度剖析/04_Rust所有权异步与后端深度剖析.md' '知识/Rust后端/Rust所有权异步与后端深度剖析.md'
move '教程文档/00_知识体系/深度剖析/05_Linux资源管理与网络子系统深度剖析.md' '知识/Linux内核/Linux资源管理与网络子系统深度剖析.md'
move '教程文档/00_知识体系/深度剖析/06_HCCL执行链路与优化深度剖析.md' '知识/集合通信/HCCL/HCCL执行链路与优化深度剖析.md'
move '教程文档/00_知识体系/深度剖析/07_NCCL拓扑算法协议与性能深度剖析.md' '知识/集合通信/NCCL/NCCL拓扑算法协议与性能深度剖析.md'
move '教程文档/00_知识体系/深度剖析/08_跨栈对照与统一排错方法.md' '知识/跨栈方法/跨栈对照与统一排错方法.md'
move '教程文档/00_知识体系/深度剖析/09_七栈术语对照与高阶自测.md' '知识/跨栈方法/七栈术语对照与高阶自测.md'

move '教程文档/00_知识体系/深度剖析/10_Vue浏览器V8代码课' '知识/前端与浏览器/代码课'
move '教程文档/00_知识体系/深度剖析/11_JavaSpringJVM代码课' '知识/Java与JVM/代码课'
move '教程文档/00_知识体系/深度剖析/12_Go后端与运行时代码课' '知识/Go后端/代码课'
move '教程文档/00_知识体系/深度剖析/13_Rust异步后端代码课' '知识/Rust后端/代码课'
move '教程文档/00_知识体系/深度剖析/14_Linux内核源码代码课' '知识/Linux内核/代码课'
move '教程文档/00_知识体系/深度剖析/15_HCCL源码代码课' '知识/集合通信/HCCL/代码课'
move '教程文档/00_知识体系/深度剖析/16_NCCL源码代码课' '知识/集合通信/NCCL/代码课'
move '教程文档/00_知识体系/深度剖析/17_集合通信统一实验框架' '知识/集合通信/统一实验框架'

# 学习：日历、专项、算法和当天任务分开。
move '教程文档/01_学习与训练/学习计划/00_总执行计划' '学习/60天总计划'
move '教程文档/01_学习与训练/学习计划/01_Vue_Java_Spring_PDF浏览平台' '学习/专项计划/Vue与Java_Spring_PDFBrowser'
move '教程文档/01_学习与训练/学习计划/02_Go_PDF任务协调服务' '学习/专项计划/Go_PDF任务协调服务'
move '教程文档/01_学习与训练/学习计划/03_Rust_PDF内容处理引擎' '学习/专项计划/Rust_PDF内容处理引擎'
move '教程文档/01_学习与训练/学习计划/04_Linux资源管理子系统_源码阅读' '学习/专项计划/Linux资源管理源码'
move '教程文档/01_学习与训练/学习计划/05_Linux网络子系统_源码阅读' '学习/专项计划/Linux网络源码'
move '教程文档/01_学习与训练/算法训练' '学习/算法训练'
move '教程文档/01_学习与训练/每日TODO' '学习/每日TODO'

# 科研与项目：研究证据和可运行工程分开。
move '教程文档/02_科研/HCCL' '科研/集合通信/HCCL'
move '教程文档/02_科研/论文笔记' '科研/集合通信/论文笔记'
move '教程文档/02_科研/研究方法' '科研/研究方法'
move '教程文档/02_科研/ONNX模型转换工具' '项目/模型工具/ONNX模型转换工具'
move '教程文档/03_系统与Linux/Linux2.6实验整理' '项目/Linux内核/Linux2.6实验'

# 书籍与阅读。
move '思考快与慢/思考，快与慢.pdf' '书籍/认知与心理/思考，快与慢.pdf'
move '教程文档/04_阅读资料/书籍' '书籍/技术阅读'
move '教程文档/04_阅读资料/读书笔记' '书籍/读书笔记'

# 工程记录：通用运维独立，项目记录回到项目内部。
move '教程文档/05_工程记录/操作总结' '工程/运维记录'
move '教程文档/05_工程记录/Hybrid_AllGather_提交说明' '项目/集合通信/Hybrid_AllGather/提交记录'
move '教程文档/05_工程记录' '项目/集合通信/Hybrid_AllGather/工程记录'

# 原归档和视图保留，但从主阅读路径移走。
move '教程文档/归档' '归档/历史资料'
move '教程文档/视图' '归档/旧视图'

# 写入新索引，并覆盖总索引中因目录变化而失效的五个链接。
rclone copyto "$layout/ROOT_README.md" "$remote/README.md"
rclone copyto "$layout/KNOWLEDGE_README.md" "$remote/知识/README.md"
rclone copyto "$layout/TECH_INDEX.md" "$remote/知识/总览/技术知识总索引与学习路线.md"
rclone copyto "$layout/BOOKS_README.md" "$remote/书籍/README.md"
rclone copyto "$layout/RESEARCH_README.md" "$remote/科研/README.md"
rclone copyto "$layout/PROJECTS_README.md" "$remote/项目/README.md"
rclone copyto "$layout/PDFBROWSER_README.md" "$remote/项目/PDFBrowser/README.md"
rclone copyto "$layout/LEARNING_README.md" "$remote/学习/README.md"
rclone copyto "$layout/ENGINEERING_README.md" "$remote/工程/README.md"
rclone copyto "$layout/MIGRATION.md" "$remote/归档/重组记录_2026-08-09.md"

# 只清除已经为空的旧目录；任何残留文件都会阻止目录被删除。
rclone rmdirs "$remote/教程文档"
rclone rmdirs "$remote/思考快与慢"

printf 'LIBRARY_REORGANIZATION_COMPLETE=1\n'
