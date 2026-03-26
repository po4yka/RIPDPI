---
title: "RIPDPI -- Инструкция по диагностике"
subtitle: "Как проверить сеть и отправить отчет"
date: "Март 2026"
author: "RIPDPI Project"
geometry: "margin=2cm"
fontsize: 12pt
mainfont: "PT Sans"
monofont: "PT Mono"
documentclass: article
numbersections: true
lang: ru
header-includes:
  - |
    ```{=latex}
    \usepackage{fancyhdr}
    \pagestyle{fancy}
    \fancyhead[L]{RIPDPI -- Инструкция по диагностике}
    \fancyhead[R]{\thepage}
    \fancyfoot[C]{}
    \usepackage{float}
    \usepackage{caption}
    \captionsetup{font=small,labelfont=bf}
    \usepackage{tcolorbox}
    \newtcolorbox{infobox}[1][]{colback=blue!5!white,colframe=blue!50!black,title=#1}
    \newtcolorbox{warnbox}[1][]{colback=orange!5!white,colframe=orange!50!black,title=#1}
    \usepackage{enumitem}
    \setlist[enumerate]{itemsep=4pt}
    \setlist[itemize]{itemsep=2pt}
    ```
---

# Что вам понадобится

- Android-телефон с версией Android 8.0 или выше
- Установленное приложение RIPDPI
- Подключение к интернету (Wi-Fi или мобильные данные)

При первом запуске приложение запросит разрешение на создание VPN-соединения -- нажмите **Continue** и подтвердите.

\begin{figure}[H]
\centering
\includegraphics[width=0.3\textwidth]{manual-assets/intro-vpn-permission.png}
\caption{Разрешение на VPN -- нажмите Continue}
\end{figure}

# Запуск диагностики

\begin{warnbox}[Важно]
Перед сканированием убедитесь, что прокси/VPN в RIPDPI \textbf{выключен} (кнопка Disconnect на главном экране). Нам нужно увидеть вашу сеть без обхода.
\end{warnbox}

\begin{figure}[H]
\centering
\includegraphics[width=0.35\textwidth]{manual-assets/home-screen.png}
\caption{Главный экран -- если подключение активно, нажмите Disconnect}
\end{figure}

\begin{enumerate}
\item Откройте приложение RIPDPI.
\item Перейдите на экран \textbf{Diagnostics} (нижняя панель навигации).
\item Выберите вкладку \textbf{Scan}.
\item В выпадающем списке профилей выберите \textbf{DPI Full}.
\item Режим сканирования: выберите \textbf{Raw Path}.
\item Нажмите \textbf{Start Scan}.
\item Дождитесь завершения -- это займет 2--5 минут.
\end{enumerate}

\begin{figure}[H]
\centering
\includegraphics[width=0.3\textwidth]{manual-assets/diag-scan-tab.png}
\caption{Вкладка Scan: выберите DPI Full и нажмите Validate (Raw Path)}
\end{figure}

Во время сканирования вы увидите результаты зондов в реальном времени:

- \textbf{Зеленый} -- всё в порядке, помех нет
- \textbf{Желтый} -- частичная проблема
- \textbf{Красный} -- заблокировано или подменено

Не закрывайте приложение до завершения сканирования.

# Отправка результатов

После завершения сканирования:

\begin{enumerate}
\item Перейдите на вкладку \textbf{Share} (в экране Diagnostics).
\item Нажмите \textbf{Export Archive}.
\item В открывшемся меню выберите способ отправки (Telegram, почта, или другой мессенджер).
\item Отправьте архив мне.
\end{enumerate}

\begin{figure}[H]
\centering
\includegraphics[width=0.3\textwidth]{manual-assets/diag-share.png}
\caption{Вкладка Share: нажмите Share archive и отправьте файл}
\end{figure}

\begin{infobox}[Что содержит архив]
Архив включает результаты всех зондов, информацию о вашей сети (тип подключения, DNS-серверы) и версию приложения. Личные данные и история посещений \textbf{не} включаются.
\end{infobox}

# Если нужна дополнительная проверка

Если я попрошу запустить пробу стратегий:

\begin{enumerate}
\item Откройте Diagnostics > вкладка \textbf{Scan}.
\item В списке профилей выберите \textbf{Quick Strategy Probe v1}.
\item Режим: \textbf{Raw Path}.
\item Нажмите \textbf{Start Scan}.
\item Дождитесь завершения (1--2 минуты).
\item Перейдите на вкладку \textbf{Share} > \textbf{Export Archive} и отправьте архив мне.
\end{enumerate}

# Если что-то пошло не так

| Проблема | Что делать |
|----------|-----------|
| Сканирование зависло | Отмените (кнопка отмены) и запустите заново |
| Приложение просит VPN-разрешение | Подтвердите разрешение |
| Все результаты красные | Это нормально -- именно это мы и хотим увидеть. Отправьте отчет |
| Нет интернета | Проверьте подключение, попробуйте переключиться между Wi-Fi и мобильными данными |
