# Native Size Report

| ABI | Library | Baseline | Current | Delta | Allowed | Status |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| arm64-v8a | libripdpi-tunnel.so | 7714816 | 7920544 | +205728 | 7845888 | regression |
| arm64-v8a | libripdpi.so | 9420800 | 10170512 | +749712 | 9551872 | regression |
| armeabi-v7a | libripdpi-tunnel.so | 4723712 | 4937424 | +213712 | 4854784 | regression |
| armeabi-v7a | libripdpi.so | 5910528 | 6501304 | +590776 | 6041600 | regression |
| x86 | libripdpi-tunnel.so | 6684672 | 6898892 | +214220 | 6815744 | regression |
| x86 | libripdpi.so | 8503296 | 9318020 | +814724 | 8634368 | regression |
| x86_64 | libripdpi-tunnel.so | 9216000 | 9422768 | +206768 | 9347072 | regression |
| x86_64 | libripdpi.so | 11149312 | 11991448 | +842136 | 11280384 | regression |

## Totals

- baseline: `63323136`
- current: `67160912`
- delta: `+3837776`
- allowed: `63585280`
- status: `regression`

## Failures

- arm64-v8a/libripdpi-tunnel.so size regression: baseline=7714816 actual=7920544 allowed=7845888
- arm64-v8a/libripdpi.so size regression: baseline=9420800 actual=10170512 allowed=9551872
- armeabi-v7a/libripdpi-tunnel.so size regression: baseline=4723712 actual=4937424 allowed=4854784
- armeabi-v7a/libripdpi.so size regression: baseline=5910528 actual=6501304 allowed=6041600
- x86/libripdpi-tunnel.so size regression: baseline=6684672 actual=6898892 allowed=6815744
- x86/libripdpi.so size regression: baseline=8503296 actual=9318020 allowed=8634368
- x86_64/libripdpi-tunnel.so size regression: baseline=9216000 actual=9422768 allowed=9347072
- x86_64/libripdpi.so size regression: baseline=11149312 actual=11991448 allowed=11280384
- Total native size regression: baseline=63323136 actual=67160912 allowed=63585280
