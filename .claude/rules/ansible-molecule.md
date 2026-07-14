---
paths:
  - "ansible/**/*.yml"
  - "ansible/**/*.yaml"
  - "ansible/**/*.j2"
---

## Ansible molecule + xray template authoring

Three failure modes ate hours of converge time during the May 2026
full-stack test pass against the sibling `ripdpi-vpn-deploy` repo. All
three are nearly invisible until the converge logs are read carefully,
and all three are load-bearing for any new molecule scenario or xray
config template touched from this workspace.

### Rule 1: molecule platforms must be in the target group, not host_vars alone

`ansible/playbooks/site.yml` targets `hosts: vpn`. If a molecule
platform is declared only at the top level of `inventory.hosts.<name>`,
or its variables go into `host_vars/<name>.yml` only, the playbook's
`hosts: vpn` selector matches NOTHING and the converge reports zero
plays applied — which reads like a success in the molecule summary.

Correct shape in `molecule.yml`:

```yaml
inventory:
  hosts:
    vpn:
      hosts:
        <container-name>:
  group_vars:
    vpn:
      ansible_user: root
      vpn:
        enable_xray_reality: true
        # ...
```

WRONG shape that silently no-ops:

```yaml
inventory:
  hosts:
    <container-name>:           # no group nesting
  host_vars:                    # NOT group_vars
    <container-name>:
      ansible_user: root
```

The give-away is `PLAY [Configure vpn] ... TASK [...] ... PLAY RECAP : ok=0 changed=0` for the container — molecule does not flag the host-not-in-group case as an error.

### Rule 2: molecule does NOT auto-load `ansible/group_vars/all.yml`

Molecule writes a temp inventory directory at run time and Ansible's
`group_vars/` auto-loading is keyed off the inventory directory, NOT
the playbook directory. The repo's `ansible/group_vars/all.yml` is
therefore INVISIBLE to molecule plays.

Every scenario must mirror the non-group-scoped keys from
`ansible/group_vars/all.yml` into the scenario's
`inventory.group_vars.all` block. When a role aborts with
`<var> is undefined` — `xray_install_root`, `hysteria_config_dir`,
`nginx_xhttp_port`, etc. — this is the reason, even when the variable
*is* set in the canonical `group_vars/all.yml`.

When adding a new top-level key in `ansible/group_vars/all.yml`,
update every `ansible/molecule/*/molecule.yml` that mirrors them in
the same PR. Quick audit:

```bash
grep -l "group_vars:" ansible/molecule/*/molecule.yml \
  | xargs -I{} sh -c 'echo "=== {} ==="; grep -A20 "group_vars:" {} | head -25'
```

### Rule 3: xray `routing.rules` of type field need at least one selector

Every entry in `routing.rules` with `"type": "field"` MUST include at
least one of the selector fields:

- `domain` (and DSL `domain:`, `full:`, `regexp:`)
- `ip`
- `port`
- `network` (`tcp`, `udp`, or `tcp,udp`)
- `source`
- `user`
- `inboundTag`
- `protocol` (`http`, `tls`, `bittorrent`, etc.)
- `attrs`

Xray v26+ rejects selectorless field rules at `start-test` with:

```
app/router: this rule has no effective fields
```

A catch-all "send everything to outboundTag X" must be expressed
explicitly — `"network": "tcp,udp"` is the canonical default. The
intuitively-correct empty form is rejected:

```jsonc
// REJECTED at start by v26+
{
  "type": "field",
  "outboundTag": "direct"
}

// ACCEPTED
{
  "type": "field",
  "network": "tcp,udp",
  "outboundTag": "direct"
}
```

Audit when editing
`ansible/roles/xray/templates/config.json.j2`. Programmatic enforcement
lives in the sibling repo's `scripts/check-templates-render.py`, which
runs the rendered config through `xray run -test -config` when a local
xray binary or cached `ghcr.io/xtls/xray-core` image is available.

### Test-secrets fixture discipline

Molecule scenarios that share a synthetic-secrets fixture (e.g.
`full-stack` and `full-stack-published`) should keep ONE source of
truth — point the sibling scenario at the canonical file via:

```yaml
provisioner:
  env:
    VPN_SECRETS_FILE: ${MOLECULE_SCENARIO_DIRECTORY}/../full-stack/test-secrets.yaml
```

Then a sha256 update or X25519 keypair regeneration lands in one file,
not many. The RIPDPI-side `test-lab/vpn-deploy/start.sh` pre-flights
this fixture before converge — see `llm-rust-prompts.md` for the
forbidden-input list.

### Cross-references

- `.claude/rules/llm-rust-prompts.md` — sentinel patterns for the
  routing-rule and inventory-shape failures above; the diff-acceptance
  gate covers AI-generated Ansible/Jinja2 diffs too.
- ripdpi-vpn-deploy `scripts/check-templates-render.py` — programmatic
  xray template lint with semantic validation via `xray -test`.
- ripdpi-vpn-deploy `scripts/validate-secrets.py` — schema gate for the
  production secrets file (the `validate-secrets` mode in the deploy
  pipeline, distinct from the test-fixture pre-flight above).
- ripdpi-vpn-deploy `ansible/molecule/full-stack/molecule.yml` and
  `ansible/molecule/full-stack-published/molecule.yml` — canonical
  examples of the correct inventory + group_vars-mirroring shape.
