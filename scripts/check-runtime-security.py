#!/usr/bin/env python3
"""Fail closed if AMCTimber's audited runtime security boundaries regress."""

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src" / "main" / "java" / "com" / "asketmc" / "timber"
errors: list[str] = []


def source(name: str) -> str:
    return (JAVA / name).read_text(encoding="utf-8")


worldguard = source("WorldGuardBridge.java")
for flag in ("BLOCK_BREAK", "BUILD", "INTERACT", "ITEM_DROP", "PVP", "DAMAGE_ANIMALS"):
    if f"Flags.{flag}" not in worldguard:
        errors.append(f"WorldGuard typed operation mapping is missing {flag}")

towny = source("TownyBridge.java")
if "isWilderness" in towny:
    errors.append("Towny must evaluate every coordinate without a wilderness shortcut")
for action in ("DESTROY", "BUILD", "SWITCH", "ITEM_USE"):
    if f"TownyPermission.ActionType.{action}" not in towny:
        errors.append(f"Towny typed operation mapping is missing {action}")

listener = source("BlockBreakListener.java")
drop_guard = listener.find("event.isDropItems()")
scan_call = listener.find("plugin.scanner().scan")
if drop_guard < 0 or scan_call < 0 or drop_guard > scan_call:
    errors.append("BlockBreakEvent drop policy must be consumed before tree scanning")
if "EventPriority.MONITOR" not in listener or "event.isCancelled()" not in listener:
    errors.append("fell commit must consume final ordinary-priority cancellation state")

session = source("FellSession.java")
if "living.damage(damage, ownerPlayer)" not in session or "living.damage(damage);" in session:
    errors.append("crush damage must be attributed to the felling player")
if "withinCrushSample" not in session or "visited >= cfg.maxCrushCandidates" not in session:
    errors.append("paced crush must revalidate delayed targets and cap expensive candidate visits")

manager = source("FellJobManager.java")
landing_auth = manager.find("canLand(")
mutation = manager.find("removeTree(journal, shape)")
if landing_auth < 0 or mutation < 0 or landing_auth > mutation:
    errors.append("landing footprint authorization must precede source mutation")
if "workAdmission.commitBlocks()" not in manager:
    errors.append("ground/protection/loot launch work must remain spent on rejected fells")

direct_delivery = []
for path in JAVA.glob("*.java"):
    if "ItemDelivery.tryDeliver" in path.read_text(encoding="utf-8"):
        direct_delivery.append(path.name)
if direct_delivery != ["PendingYield.java"]:
    errors.append("all item creation must route through the paced PendingYield dispatcher: "
                  + ", ".join(direct_delivery))
if "while" in source("PendingYield.java"):
    errors.append("one pending-yield attempt may not drain a ledger loop")
if "ProtectionHook.Action.ITEM_DROP" not in source("PendingYield.java"):
    errors.append("durable item delivery must reauthorize the persisted actor and item-drop action")
if "protection.active()" not in source("PendingYield.java"):
    errors.append("actorless legacy delivery must fail closed while protection is active")
if "material.isItem()" not in source("PendingYieldFile.java"):
    errors.append("recovery material validation must reject non-item block states")
if "stagedYields" not in source("FelledTrunkStore.java"):
    errors.append("terminal yield must remain non-deliverable until its journal checkpoint succeeds")

if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    raise SystemExit(1)

print("runtime security invariants: PASS")
