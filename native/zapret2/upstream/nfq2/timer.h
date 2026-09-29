#pragma once

#include <stdint.h>
#include <stdbool.h>

#include "pools.h"

typedef struct timer_pool {
	char *str;		/* key */
	int func_ref;
	uint64_t period;
	bool oneshot;
	int lua_ref;
	uint64_t bt_next;	/* next activation time */
	unsigned int n, fires;
	UT_hash_handle hh;	/* makes this structure hashable */
} timer_pool;

void TimerPoolDestroy(timer_pool **pp);
struct timer_pool *TimerPoolSearch(timer_pool *p, const char *str);
struct timer_pool *TimerPoolAdd(timer_pool **pp, const char *str, uint64_t period, bool oneshot);
void TimerPoolDel(timer_pool **pp, timer_pool *p);
// return next timer fire time
uint64_t TimerPoolRun(timer_pool **pp, bool *dirty, uint64_t bt);
uint64_t TimerPoolNext(const timer_pool *p, bool *dirty);
