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
\item Прокрутите список профилей до группы \textbf{Network Full}.
\item Выберите профиль \textbf{Russia DPI Full}.
\item Нажмите \textbf{Validate} (Raw Path).
\item Дождитесь завершения -- это займет 2--5 минут.
\end{enumerate}

\begin{figure}[H]
\centering
\includegraphics[width=0.3\textwidth]{manual-assets/diag-scan-tab.png}
\caption{Вкладка Scan: выберите Russia DPI Full и нажмите Validate}
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
\item Нажмите \textbf{Share archive}.
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

Если я попрошу запустить пробу стратегий обхода:

\begin{enumerate}
\item Откройте Diagnostics > вкладка \textbf{Scan}.
\item Прокрутите список до группы \textbf{Automatic Probing}.
\item Выберите профиль \textbf{Automatic probing}.
\item Нажмите \textbf{Recommend} (Raw Path).
\item Дождитесь завершения (1--2 минуты).
\item Перейдите на вкладку \textbf{Share} > \textbf{Share archive} и отправьте архив мне.
\end{enumerate}

Для более полной проверки я могу попросить запустить \textbf{Automatic audit} (группа \textbf{Automatic Audit}) -- это займет 5--10 минут, но проверит все возможные комбинации.

# Если приложение упало

Если приложение вылетело (краш), после перезапуска:

\begin{enumerate}
\item Откройте Diagnostics > вкладка \textbf{Share}.
\item Нажмите \textbf{Save logs} -- сохраните файл.
\item Отправьте сохраненный файл \texttt{ripdpi.log} мне.
\end{enumerate}

# Если что-то пошло не так

| Проблема | Что делать |
|----------|-----------|
| Сканирование зависло | Отмените (кнопка отмены) и запустите заново |
| Приложение просит VPN-разрешение | Подтвердите разрешение |
| Все результаты красные | Это нормально -- именно это мы и хотим увидеть. Отправьте отчет |
| Нет интернета | Проверьте подключение, попробуйте переключиться между Wi-Fi и мобильными данными |
| Не вижу нужный профиль | Прокрутите список профилей вниз -- они сгруппированы по категориям |
