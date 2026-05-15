
# IdeaCards

![Android Badge](https://img.shields.io/badge/Platform-Android-green?logo=android) 
![Java Badge](https://img.shields.io/badge/Language-Java-orange?logo=java) 
![Room Badge](https://img.shields.io/badge/Database-Room-blue)
![API Badge](https://img.shields.io/badge/TargetSDK-34-red)

> **“捕获灵感，沉淀思考。”** —— 这是一个专为捕捉碎片化想法而设计的 Android 应用，采用流式输入逻辑，结合现代化的数据持久化方案与系统级交互规范。

---

## 🌟 项目简介

在快节奏的日常生活中，灵感往往转瞬即逝。**IdeaCards** 模仿即时通讯软件的交互逻辑，让记录想法像聊天一样简单快捷。用户通过底部输入框即时发送“灵感”，系统自动将其存入底层数据库，并支持导出后续的深度归档与整理。

---

## ✨ 核心功能

* **流式极速记录 (RecyclerView)**：仿对话气泡的主界面，采用 `RecyclerView` 实现。每条卡片包含内容、时间戳、处理状态等多个字段，满足复杂列表展示要求。
* **文学灵感注入 (Network API)**：每次启动应用，通过子线程异步请求 **Hitokoto (一言) API**，解析 JSON 数据并在首页动态显示文学名言，激发记录欲望。
* **归档管理中心 (Room DB)**：基于 **Room 框架** 构建的数据层。支持对碎片记录的“增、删、改、查”全生命周期管理，确保数据本地持久化。
* **智能整理提醒 (Notification)**：针对 `targetSdk 34` 实现的高级通知功能。包含动态权限申请逻辑与 `NotificationChannel` 适配，提醒用户及时回顾。
* **数据开放接口 (ContentProvider)**：内置自定义 `ContentProvider`，通过标准 `URI` 对外暴露数据访问能力，体现 Android 跨组件共享的设计理念。

---

## 🛠️ 技术栈与实现

* **开发语言**：Java (JDK 17)
* **数据库**：Room Persistence Library (ORM 映射)
* **架构模式**：基本分层架构 (UI 层、Data 访问层、Provider 接口层)
* **网络访问**：原生 `HttpURLConnection` + `JSON` 手动解析 (非主线程执行)
* **系统适配**：适配 Android 14 (API 34) 运行时权限模型
* **构建工具**：Gradle (Kotlin DSL / Groovy)

---

## 📂 项目结构描述

```text
com.example.ideacards
├── data
│   ├── entity      # Room 数据库实体类 (NoteEntity)
│   ├── dao         # 数据库访问接口 (NoteDao)
│   ├── db          # 数据库单例配置 (AppDatabase)
│   └── provider    # ContentProvider 实现 (NoteContract / NoteContentProvider)
├── ui
│   ├── adapter     # RecyclerView 适配器 (BubbleAdapter / NoteListAdapter)
│   ├── main        # 主界面 (MainActivity) - 负责气泡流展示与网络请求
│   ├── archive     # 归档页 (ArchiveActivity) - 负责列表管理与通知触发
│   └── detail      # 详情页 (DetailActivity) - 负责单条笔记的编辑与删除
└── utils           # 工具类 (网络请求工具、时间格式化工具)
````

---

## 🚀 快速开始

1. **环境配置**：建议使用 Android Studio Flamingo (2022.2.1) 或更高版本。
    
2. **版本要求**：`minSdk: 24`，`targetSdk: 34`。
    
3. **运行权限**：初次运行点击“提醒我整理”按钮时，请在弹出的系统对话框中授予“通知权限”。
    
4. **网络要求**：请确保测试环境网络连接正常，以便应用获取“每日一句”灵感。
    

---
