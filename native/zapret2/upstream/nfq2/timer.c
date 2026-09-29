#include "timer.h"
#include "params.h"
#include "helpers.h"

#include "lua.h"

#undef uthash_nonfatal_oom
#define uthash_nonfatal_oom(elt) ut_oom_recover(elt)

static bool oom = false;
static void ut_oom_recover(void *elem)
{
	oom = true;
}

static unsigned int timer_n=0;


static void TimerPoolDestroyItem(timer_pool *elem)
{
	free(elem->str);
	luaL_unref(params.L, LUA_REGISTRYINDEX, elem->lua_ref);
	luaL_unref(params.L, LUA_REGISTRYINDEX, elem->func_ref);
}
void TimerPoolDel(timer_pool **pp, timer_pool *p)
{
	TimerPoolDestroyItem(p);
	HASH_DEL(*pp,p);
	free(p);
}
void TimerPoolDestroy(timer_pool **pp)
{
	timer_pool *elem, *tmp;
	HASH_ITER(hh, *pp, elem, tmp) TimerPoolDel(pp,elem);
}
struct timer_pool *TimerPoolSearch(timer_pool *p, const char *str)
{
	timer_pool *elem_find;
	HASH_FIND_STR(p, str, elem_find);
	return elem_find;
}
struct timer_pool *TimerPoolAdd(timer_pool **pp, const char *str, uint64_t period, bool oneshot)
{
	ADD_STR_POOL(timer_pool, pp, str, strlen(str))
	elem->lua_ref = LUA_NOREF;
	elem->func_ref = LUA_NOREF;
	elem->period = period;
	elem->oneshot = oneshot;
	elem->bt_next = boottime_ms() + elem->period;
	elem->n = ++timer_n;
	elem->fires = 0;
	return elem;
}

static bool TimerPoolRunTimer(timer_pool *p)
{
	lua_rawgeti(params.L, LUA_REGISTRYINDEX, p->func_ref);
	lua_pushstring(params.L, p->str);
	lua_rawgeti(params.L, LUA_REGISTRYINDEX, p->lua_ref);
	p->fires++;
	DLOG("\ntimer: '%s' period %llu oneshot %u fires=%u\n",p->str,p->period,p->oneshot,p->fires);
	int status = lua_pcall(params.L, 2, 0, 0);
	if (status)
	{
		lua_dlog_error();
		return false;
	}
	return true;
}
uint64_t TimerPoolNext(const timer_pool *p, bool *dirty)
{
	*dirty = false;
	if (!p) return 0;

	const timer_pool *elem, *tmp;
	uint64_t mintime=0x7FFFFFFFFFFFFFFF;

	HASH_ITER(hh, p, elem, tmp)
	{
		if (elem->bt_next < mintime) mintime = elem->bt_next;
	}
	return mintime;
}
uint64_t TimerPoolRun(timer_pool **pp, bool *dirty, uint64_t bt)
{
	if (!*pp)
	{
		*dirty = false;
		return 0; // no timers
	}

	timer_pool *elem, *tmp, *p;
	char *name;
	const char *del;
	unsigned int n;
	uint64_t mintime;

	if (!bt) bt = boottime_ms();
again:
	mintime=0x7FFFFFFFFFFFFFFF;
	*dirty = false;
	HASH_ITER(hh, *pp, elem, tmp)
	{
		if (bt >= elem->bt_next)
		{
			if ((name = strdup(elem->str)))
			{
				n = elem->n;

				del = NULL;
				if (!TimerPoolRunTimer(elem))
					del = "timer: '%s' deleted because of error\n";

				if (*dirty)
				{
					// timer function could delete the timer or recreate with the same name
					p = TimerPoolSearch(*pp, name);
					if (p!=elem || p->n!=n) elem = NULL; // timer deleted or recreated with the same name (but another 'n')
				}
				if (elem)
				{
					if (!del && elem->oneshot)
						del = "timer: '%s' deleted because of oneshot\n";
					// elem is valid, not deleted and not recreated
					if (del)
					{
						DLOG(del,name);
						TimerPoolDel(pp, elem);
						elem = NULL;
					}
					else
					{
						// in case process was freezed (--ssid-filter/nlm-filter stop in winws for example)
						// do not use previous activation time or timer can hit multiple times
						// use current time instead to prevent bulk activation
						elem->bt_next = bt + elem->period;
					}
				}

				free(name);

				if (*dirty)
					goto again; // they may have deleted or created any number of timers, HASH_ITER may fail or access invalid pointers - restart
			}
			else // unlikely
			{
				if (elem->oneshot)
				{
					DLOG_ERR("timer: '%s' deleted because of out of memory\n", elem->str);
					TimerPoolDel(pp, elem);
					elem = NULL;
				}
				else
				{
					DLOG_ERR("timer: '%s' skipped because of out of memory\n", elem->str);
					elem->bt_next = bt + elem->period;
				}
			}
		}
		if (elem && (elem->bt_next < mintime)) mintime = elem->bt_next;
	}
	if (!*pp) return 0; // no timers
	return mintime;
}
