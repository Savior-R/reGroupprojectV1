/* ==========================================================================
   三尺讲台 · Prototype behaviour
   --------------------------------------------------------------------------
   无构建、无依赖。负责：图标注入、顶部导航、原型地图、标签页、抽屉、
   下拉菜单、Toast。页面自身的演示交互写在各自 HTML 末尾。
   ========================================================================== */

(function () {
  "use strict";

  /* ------------------------------------------------------------------------
     Icons — hand-authored 24×24 stroke icons.
     Geometry follows common Lucide-style conventions; if this set is replaced
     by Lucide itself, keep Lucide's ISC license notice in the repository.
     ------------------------------------------------------------------------ */
  const ICONS = {
    "arrow-left": '<path d="M19 12H5"/><path d="m11 18-6-6 6-6"/>',
    "arrow-right": '<path d="M5 12h14"/><path d="m13 6 6 6-6 6"/>',
    "chevron-down": '<path d="m6 9 6 6 6-6"/>',
    "chevron-right": '<path d="m9 6 6 6-6 6"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    "check-circle": '<circle cx="12" cy="12" r="9"/><path d="m8 12 2.5 2.5L16 9"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/>',
    "book-open": '<path d="M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2V4Z"/><path d="M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7V4Z"/>',
    clipboard: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4.5V3h6v1.5"/><path d="M9 10h6M9 14h6M9 18h3"/>',
    graduation: '<path d="m2 9 10-5 10 5-10 5L2 9Z"/><path d="M6 11.5V17c3 2 9 2 12 0v-5.5"/><path d="M22 9v6"/>',
    sparkles: '<path d="m12 3 1.4 4.2L18 9l-4.6 1.8L12 15l-1.4-4.2L6 9l4.6-1.8L12 3Z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z"/>',
    chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1.08-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1.08 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c.26.6.85 1 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>',
    play: '<path d="m8 5 12 7-12 7V5Z"/>',
    pause: '<path d="M8 5v14M16 5v14"/>',
    mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0"/><path d="M12 17v4M8 21h8"/>',
    camera: '<path d="M14.5 4h-5L8 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-4l-1.5-3Z"/><circle cx="12" cy="13" r="3.2"/>',
    monitor: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
    file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/><path d="M8 13h8M8 17h5"/>',
    upload: '<path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M20 16v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2"/>',
    download: '<path d="M12 4v12"/><path d="m7 11 5 5 5-5"/><path d="M20 16v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2"/>',
    more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    filter: '<path d="M4 5h16l-6 7v5l-4 2v-7L4 5Z"/>',
    target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/><circle cx="12" cy="12" r="1"/>',
    message: '<path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10Z"/>',
    hand: '<path d="M8 13V5a2 2 0 0 1 4 0v6"/><path d="M12 11V4a2 2 0 0 1 4 0v7"/><path d="M16 11V6a2 2 0 0 1 4 0v8a7 7 0 0 1-7 7h-1a7 7 0 0 1-7-7v-2a2 2 0 0 1 4 0"/>',
    "alert-triangle": '<path d="m10.3 3.9-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.7-3.1l-8-14a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    lock: '<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
    wechat: '<path d="M21 11.5a8.4 8.4 0 0 1-8.5 8.3 9.6 9.6 0 0 1-3.8-.8L4 20l1.3-3.6A8 8 0 0 1 4 11.5 8.4 8.4 0 0 1 12.5 3 8.4 8.4 0 0 1 21 11.5Z"/><path d="M8 10h.01M13 10h.01"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M17 6l3 3M15 8l3 3"/>',
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
    "eye-off": '<path d="m3 3 18 18"/><path d="M10.6 5.2A10.7 10.7 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-2.2 3.2"/><path d="M6.6 6.6A17 17 0 0 0 2 12s3.5 7 10 7a10.7 10.7 0 0 0 4.4-1"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
    refresh: '<path d="M20 11a8 8 0 0 0-14.9-3.9L3 10"/><path d="M3 5v5h5"/><path d="M4 13a8 8 0 0 0 14.9 3.9L21 14"/><path d="M21 19v-5h-5"/>',
    fullscreen: '<path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3"/>',
    volume: '<path d="M11 5 6 9H2v6h4l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/>',
    "skip-forward": '<path d="m5 4 10 8-10 8V4Z"/><path d="M19 5v14"/>',
    edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v5M14 11v5"/>',
    archive: '<rect x="3" y="3" width="18" height="5" rx="1"/><path d="M5 8v11a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8M10 12h4"/>',
    external: '<path d="M15 3h6v6M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3 6h.01M3 12h.01M3 18h.01"/>',
    home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1V10Z"/>',
    layers: '<path d="m12 2 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 17l9 5 9-5"/>',
    star: '<path d="m12 2 3 6 6.5 1-4.7 4.6 1.1 6.4L12 17l-5.9 3 1.1-6.4L2.5 9 9 8l3-6Z"/>',
    "trending-up": '<path d="m3 17 6-6 4 4 7-8"/><path d="M14 7h6v6"/>',
    wifi: '<path d="M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 20h.01"/>',
    logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5M21 12H9"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    sliders: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h10M18 18h2"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="16" cy="18" r="2"/>',
    circle: '<circle cx="12" cy="12" r="8"/>',
    podium: '<path d="M4 21h16M6 21V10h12v11M9 10V4h6v6M12 4V2"/>'
  };

  function iconSvg(name) {
    const body = ICONS[name];
    if (!body) return "";
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + body + "</svg>";
  }

  function hydrateIcons(root) {
    (root || document).querySelectorAll("[data-icon]").forEach(function (el) {
      if (el.dataset.iconReady === "1") return;
      const name = el.dataset.icon;
      if (!ICONS[name]) return;
      el.innerHTML = iconSvg(name);
      el.dataset.iconReady = "1";
    });
  }

  /* ------------------------------------------------------------------------
     Shell configuration
     ------------------------------------------------------------------------ */
  const NAV = {
    student: [
      { key: "courses", label: "课程", href: "courses.html?role=student" },
      { key: "tasks", label: "任务", href: "student-tasks.html" },
      { key: "training", label: "训练", href: "student-training.html" },
      { key: "results", label: "我的成绩", href: "student-results.html" }
    ],
    teacher: [
      { key: "courses", label: "课程", href: "courses.html?role=teacher" },
      { key: "question-bank", label: "题库", href: "question-bank.html" },
      { key: "training-templates", label: "训练模板", href: "teacher-training.html" }
    ],
    admin: [
      { key: "admin-org", label: "学校与组织", href: "admin-org.html" },
      { key: "admin-teachers", label: "教师管理", href: "admin-teachers.html" },
      { key: "courses", label: "课程管理", href: "courses.html?role=admin" }
    ]
  };

  const ACCOUNTS = {
    student: {
      name: "林小雨",
      role: "学生 · 汉语言文学",
      initial: "林",
      tone: "avatar--cinnabar",
      home: "student-home.html"
    },
    teacher: {
      name: "王宁",
      role: "教师 · 语文教学论",
      initial: "王",
      tone: "avatar--gold",
      home: "courses.html?role=teacher"
    },
    admin: {
      name: "陈明",
      role: "管理员 · 教务处",
      initial: "陈",
      tone: "avatar--blue",
      home: "admin-org.html"
    }
  };

  function renderAppHeader() {
    const host = document.querySelector("[data-app-header]");
    const existing = document.querySelector(".app-header");
    if (!host && !existing) return;

    const role = document.body.dataset.role || "student";
    const active = document.body.dataset.nav || "";
    const account = ACCOUNTS[role] || ACCOUNTS.student;
    const navItems = NAV[role] || NAV.student;

    const navHtml = navItems
      .map(function (item) {
        const isActive = item.key === active;
        return (
          '<a class="nav-link' + (isActive ? " is-active" : "") + '" href="' + item.href + '"' +
          (isActive ? ' aria-current="page"' : "") + ">" + item.label + "</a>"
        );
      })
      .join("");

    const headerHtml =
      '<header class="app-header">' +
        '<a class="brand" href="' + account.home + '" aria-label="三尺讲台首页">' +
          '<span class="seal seal--sm" aria-hidden="true">三</span>' +
          '<span class="brand__text">' +
            '<span class="brand__name">三尺讲台</span>' +
            '<span class="brand__sub">TEACHING STUDIO</span>' +
          "</span>" +
        "</a>" +
        '<nav class="app-header__nav" aria-label="主导航">' + navHtml + "</nav>" +
        '<div class="header-tools">' +
          '<button class="icon-btn" type="button" aria-label="全局搜索" data-toast="全局搜索为原型占位，后续接入课程 / 任务 / 学生检索。">' +
            '<span data-icon="search"></span>' +
          "</button>" +
          '<button class="icon-btn" type="button" aria-label="通知" data-toast="3 条新提醒：2 个任务待提交，1 份报告已完成。">' +
            '<span data-icon="bell" class="has-dot"></span>' +
          "</button>" +
          '<button class="account" type="button" data-menu-trigger="account-menu" aria-expanded="false" aria-controls="account-menu">' +
            '<span class="avatar avatar--sm ' + account.tone + '" aria-hidden="true">' + account.initial + "</span>" +
            '<span class="account__name">' + account.name + "</span>" +
            '<span data-icon="chevron-down"></span>' +
          "</button>" +
          '<div class="account-menu" id="account-menu" hidden>' +
            '<div class="account-menu__head">' +
              '<span class="avatar ' + account.tone + '" aria-hidden="true">' + account.initial + "</span>" +
              '<div class="account-menu__meta">' +
                '<div class="account-menu__name">' + account.name + "</div>" +
                '<div class="account-menu__role">' + account.role + "</div>" +
              "</div>" +
            "</div>" +
            '<a class="account-menu__item" href="security.html"><span data-icon="shield"></span>账号与安全</a>' +
            '<a class="account-menu__item" href="index.html"><span data-icon="layers"></span>原型说明与页面地图</a>' +
            '<button class="account-menu__item account-menu__item--danger" type="button" data-toast="原型演示：退出后将回到登录页。"><span data-icon="logout"></span>退出登录</button>' +
          "</div>" +
        "</div>" +
      "</header>";

    if (host) {
      host.outerHTML = headerHtml;
    } else {
      existing.outerHTML = headerHtml;
    }
    hydrateIcons(document);
  }

  /* ------------------------------------------------------------------------
     Prototype map
     ------------------------------------------------------------------------ */
  const SCREEN_GROUPS = [
    {
      group: "总览",
      items: [
        { num: "00", name: "原型说明", href: "index.html" },
        { num: "01", name: "设计系统", href: "design-system.html" }
      ]
    },
    {
      group: "认证与账号",
      items: [
        { num: "02", name: "登录", href: "login.html" },
        { num: "03", name: "注册", href: "register.html" },
        { num: "04", name: "账号与安全", href: "security.html" },
        { num: "05", name: "403 / 404", href: "errors.html" }
      ]
    },
    {
      group: "学生",
      items: [
        { num: "06", name: "学生工作台", href: "student-home.html" },
        { num: "07", name: "课程（角色视图）", href: "courses.html" },
        { num: "08", name: "课程详情（学生）", href: "course-detail.html?role=student" },
        { num: "09", name: "我的任务", href: "student-tasks.html" },
        { num: "10", name: "课前检查", href: "student-task-detail.html" },
        { num: "11", name: "课堂舞台", href: "classroom.html" },
        { num: "12", name: "结构化面试", href: "interview.html" },
        { num: "13", name: "回看与提交", href: "review.html" },
        { num: "14", name: "AI 评测报告", href: "report.html" },
        { num: "15", name: "我的成绩（按课程）", href: "student-results.html" },
        { num: "16", name: "课程成绩详情", href: "student-course-results.html" },
        { num: "17", name: "训练", href: "student-training.html" },
        { num: "18", name: "训练配置", href: "training-config.html" },
        { num: "18A", name: "学生模板创建 / 编辑", href: "student-template-editor.html" }
      ]
    },
    {
      group: "教师",
      items: [
        { num: "19", name: "课程任务", href: "teacher-tasks.html" },
        { num: "20", name: "任务编辑", href: "task-editor.html" },
        { num: "21", name: "任务评分", href: "grading.html" },
        { num: "22", name: "任务统计", href: "stats.html" },
        { num: "23", name: "结构化题库", href: "question-bank.html" },
        { num: "24", name: "训练模板", href: "teacher-training.html" },
        { num: "25", name: "模板查看", href: "teacher-template-detail.html" },
        { num: "26", name: "模板创建 / 编辑", href: "teacher-template-editor.html" },
        { num: "27", name: "课程详情（教师）", href: "course-detail.html?role=teacher" },
        { num: "30", name: "创建课程", href: "teacher-course-create.html" },
        { num: "31", name: "题库类别管理", href: "question-categories.html" },
        { num: "32", name: "课程学生管理", href: "teacher-course-students.html" }
      ]
    },
    {
      group: "管理员",
      items: [
        { num: "28", name: "学校与组织", href: "admin-org.html" },
        { num: "29", name: "教师管理", href: "admin-teachers.html" },
        { num: "33", name: "课程详情（管理员）", href: "admin-course-detail.html" }
      ]
    }
  ];

  function currentFile() {
    const parts = window.location.pathname.split("/");
    const last = parts[parts.length - 1];
    return last || "index.html";
  }

  function renderProtoMap() {
    if (document.querySelector(".proto-fab")) return;
    const activeFile = currentFile();

    const groupsHtml = SCREEN_GROUPS.map(function (group) {
      const links = group.items
        .map(function (item) {
          const isActive = item.href.split("?")[0] === activeFile;
          return (
            '<a class="proto-map__link' + (isActive ? " is-active" : "") + '" href="' + item.href + '">' +
              '<span class="proto-map__num">' + item.num + "</span>" +
              "<span>" + item.name + "</span>" +
            "</a>"
          );
        })
        .join("");
      return '<div class="proto-map__group">' + group.group + "</div>" + links;
    }).join("");

    const wrap = document.createElement("div");
    wrap.innerHTML =
      '<button class="proto-fab" type="button" data-proto-toggle aria-expanded="false" aria-controls="proto-map">' +
        '<span data-icon="grid"></span><span>原型地图</span>' +
      "</button>" +
      '<aside class="proto-map" id="proto-map" aria-label="原型页面地图">' +
        '<div class="proto-map__head">' +
          "<div>" +
            '<div class="proto-map__title">三尺讲台 · 原型 V1.5</div>' +
            '<div class="proto-map__sub">点击任意页面跳转 · 按 ? 打开 / 关闭</div>' +
          "</div>" +
          '<button class="icon-btn" type="button" data-proto-close aria-label="关闭原型地图"><span data-icon="x"></span></button>' +
        "</div>" +
        '<div class="proto-map__body">' + groupsHtml + "</div>" +
      "</aside>";

    document.body.appendChild(wrap);
    hydrateIcons(wrap);
  }

  function toggleProtoMap(force) {
    const map = document.getElementById("proto-map");
    const fab = document.querySelector("[data-proto-toggle]");
    if (!map || !fab) return;
    const next = typeof force === "boolean" ? force : !map.classList.contains("is-open");
    map.classList.toggle("is-open", next);
    fab.setAttribute("aria-expanded", String(next));
  }

  /* ------------------------------------------------------------------------
     Layer open / close
     ------------------------------------------------------------------------ */
  let lastFocused = null;

  function openLayer(id) {
    const layer = document.getElementById(id);
    if (!layer) return;
    document.querySelectorAll(".drawer.is-open, .modal.is-open").forEach(function (el) {
      if (el !== layer && !layer.hasAttribute("data-layer-stack")) closeLayer(el.id, true);
    });
    lastFocused = document.activeElement;
    layer.classList.add("is-open");
    layer.setAttribute("aria-hidden", "false");
    const overlay = document.querySelector('[data-overlay-for="' + id + '"]');
    if (overlay) overlay.classList.add("is-open");
    document.body.style.overflow = "hidden";
    const focusable = layer.querySelector("input, textarea, select, button, [href]");
    if (focusable) window.setTimeout(function () { focusable.focus(); }, 80);
  }

  function closeLayer(id, keepFocus) {
    const layer = document.getElementById(id);
    if (!layer) return;
    layer.classList.remove("is-open");
    layer.setAttribute("aria-hidden", "true");
    const overlay = document.querySelector('[data-overlay-for="' + id + '"]');
    if (overlay) overlay.classList.remove("is-open");
    if (!document.querySelector(".drawer.is-open, .modal.is-open")) {
      document.body.style.overflow = "";
    }
    if (!keepFocus && lastFocused && typeof lastFocused.focus === "function") {
      lastFocused.focus();
    }
  }

  function closeAllLayers() {
    document.querySelectorAll(".drawer.is-open, .modal.is-open").forEach(function (el) {
      closeLayer(el.id, true);
    });
  }

  /* ------------------------------------------------------------------------
     Toast
     ------------------------------------------------------------------------ */
  let toastTimer = null;

  function showToast(message) {
    let toast = document.querySelector(".toast");
    if (!toast) {
      toast = document.createElement("div");
      toast.className = "toast";
      toast.setAttribute("role", "status");
      toast.setAttribute("aria-live", "polite");
      document.body.appendChild(toast);
      hydrateIcons(toast);
    }
    toast.innerHTML = '<span data-icon="info"></span><span>' + message + "</span>";
    hydrateIcons(toast);
    window.clearTimeout(toastTimer);
    window.requestAnimationFrame(function () { toast.classList.add("is-visible"); });
    toastTimer = window.setTimeout(function () {
      toast.classList.remove("is-visible");
    }, 2800);
  }

  /* ------------------------------------------------------------------------
     Tabs
     ------------------------------------------------------------------------ */
  function selectTab(groupName, value) {
    const group = document.querySelector('[data-tabs="' + groupName + '"]');
    if (!group) return;
    group.querySelectorAll("[data-tab]").forEach(function (btn) {
      const selected = btn.dataset.tab === value;
      btn.setAttribute("aria-selected", String(selected));
      btn.classList.toggle("is-active", selected);
    });
    document.querySelectorAll('[data-panel-group="' + groupName + '"] [data-panel]').forEach(function (panel) {
      panel.hidden = panel.dataset.panel !== value;
    });
  }

  function initTabs() {
    document.querySelectorAll("[data-tabs]").forEach(function (group) {
      const name = group.dataset.tabs;
      const buttons = group.querySelectorAll("[data-tab]");
      if (!buttons.length) return;
      const current = group.querySelector('[data-tab][aria-selected="true"]') || buttons[0];
      selectTab(name, current.dataset.tab);
    });
  }

  /* ------------------------------------------------------------------------
     Menus
     ------------------------------------------------------------------------ */
  function closeMenus(except) {
    document.querySelectorAll("[data-menu-trigger]").forEach(function (trigger) {
      const target = document.getElementById(trigger.dataset.menuTrigger);
      if (!target || target === except) return;
      target.hidden = true;
      trigger.setAttribute("aria-expanded", "false");
    });
  }

  /* ------------------------------------------------------------------------
     Event delegation
     ------------------------------------------------------------------------ */
  document.addEventListener("click", function (event) {
    const tabButton = event.target.closest("[data-tabs] [data-tab]");
    if (tabButton) {
      const group = tabButton.closest("[data-tabs]");
      selectTab(group.dataset.tabs, tabButton.dataset.tab);
      return;
    }

    const menuTrigger = event.target.closest("[data-menu-trigger]");
    if (menuTrigger) {
      const target = document.getElementById(menuTrigger.dataset.menuTrigger);
      if (target) {
        const willOpen = target.hidden;
        closeMenus(target);
        target.hidden = !willOpen;
        menuTrigger.setAttribute("aria-expanded", String(willOpen));
      }
      event.stopPropagation();
      return;
    }

    const opener = event.target.closest("[data-open]");
    if (opener) {
      openLayer(opener.dataset.open);
      return;
    }

    const closer = event.target.closest("[data-close]");
    if (closer) {
      closeLayer(closer.dataset.close || closer.closest(".drawer, .modal")?.id);
      if (closer.dataset.toast) showToast(closer.dataset.toast);
      return;
    }

    const overlay = event.target.closest(".overlay[data-overlay-for]");
    if (overlay) {
      closeLayer(overlay.dataset.overlayFor);
      return;
    }

    const toastTrigger = event.target.closest("[data-toast]");
    if (toastTrigger) {
      if (toastTrigger.tagName === "A") event.preventDefault();
      showToast(toastTrigger.dataset.toast);
      return;
    }

    const protoToggle = event.target.closest("[data-proto-toggle]");
    if (protoToggle) {
      toggleProtoMap();
      event.stopPropagation();
      return;
    }

    const protoClose = event.target.closest("[data-proto-close]");
    if (protoClose) {
      toggleProtoMap(false);
      return;
    }

    if (!event.target.closest(".account-menu")) {
      closeMenus();
    }

    const map = document.getElementById("proto-map");
    if (map && map.classList.contains("is-open") && !event.target.closest(".proto-map") && !event.target.closest("[data-proto-toggle]")) {
      toggleProtoMap(false);
    }
  });

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      const openLayers = document.querySelectorAll(".drawer.is-open, .modal.is-open");
      const openLayerEl = openLayers[openLayers.length - 1];
      if (openLayerEl) {
        closeLayer(openLayerEl.id);
        return;
      }
      closeMenus();
      toggleProtoMap(false);
      return;
    }

    if (event.key === "?" || (event.key === "/" && event.shiftKey)) {
      const tag = (event.target.tagName || "").toLowerCase();
      if (tag === "input" || tag === "textarea" || tag === "select") return;
      event.preventDefault();
      toggleProtoMap();
    }
  });

  /* ------------------------------------------------------------------------
     Boot
     ------------------------------------------------------------------------ */
  function boot() {
    renderAppHeader();
    renderProtoMap();
    hydrateIcons(document);
    initTabs();

    // Page-level entrance motion is intentionally opt-in via `.rise-in` /
    // `.fade-in`; don't hide content if a headless renderer or reduced-motion
    // environment skips the animation.
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }

  /* expose small helpers for page-level demos */
  window.PrototypeUI = {
    icon: iconSvg,
    hydrateIcons: hydrateIcons,
    renderHeader: renderAppHeader,
    toast: showToast,
    openLayer: openLayer,
    closeLayer: closeLayer,
    selectTab: selectTab
  };
})();
