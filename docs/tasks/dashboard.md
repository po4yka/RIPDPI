# Task Dashboard — RIPDPI

## Doing

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #status/doing
sort by priority
sort by due
group by path
```

## Review queue

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #status/review
sort by priority
sort by due
group by path
```

## Blocked

```tasks
not done
tag includes #task
tag includes #blocked
tag includes #repo/RIPDPI
sort by due
group by path
```

## Todo (ready to start)

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #status/todo
sort by priority
sort by due
group by tags
```

## Backlog

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #status/backlog
sort by priority
group by tags
```

## All open tasks

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
sort by status
sort by priority
group by path
```

## Epics

```tasks
not done
tag includes #task
tag includes #repo/RIPDPI
tag includes #area/epic
sort by priority
```

## Structured views

- [views/all-tasks](views/all-tasks.base) — full table (all issues, sortable)
- [views/by-epic](views/by-epic.base) — tasks grouped by parent epic
- [views/by-area](views/by-area.base) — tasks grouped by area
- [views/by-priority](views/by-priority.base) — tasks grouped by priority
