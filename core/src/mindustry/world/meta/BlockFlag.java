package mindustry.world.meta;

/** Stores special flags of blocks for easy querying. */
public enum BlockFlag{
    /** Enemy core; primary target for all units. */
    core,
    /** Vault/container/etc */
    storage,
    /** Something that generates power. */
    generator,
    /** Any turret. */
    turret,
    /** A block that transforms resources. */
    factory,
    /** AURORA: unit factory / reconstructor (ulocate) — Завод юнітів / реконструктор (ulocate). */
    ufactory,
    /** Repair point. */
    repair,
    /** AURORA: block repair: mender, regen projector (ulocate); replaces blockRepair — Ремонт блоків: мендер, регенератор (ulocate); замість blockRepair. */
    brepair,
    /** Block that stored power for resupply. */
    battery,
    /** Any reactor block. */
    reactor,
    /** Blocks that extinguishes fires. */
    extinguisher,
    /** Is a drill. */
    drill,
    /** Force projector block. */
    shield,
    /** AURORA: walls and shock mines (ulocate) — Стіни й шокові міни (ulocate). */
    wall,
    /** AURORA: overdrive projector (ulocate) — Прискорювач (ulocate). */
    overdrive,
    /** AURORA: logic blocks: processors, displays, memory, switches (ulocate) — Логічні блоки: процесори, дисплеї, пам'ять, перемикачі (ulocate). */
    logic,
    /** AURORA: power nodes and diodes (ulocate) — Вузли енергії й діоди (ulocate). */
    powernode,
    /** AURORA: item/liquid transport: conveyors, bridges, routers, unloaders (ulocate) — Транспорт предметів/рідин: конвеєри, мости, роутери, розвантажувачі (ulocate). */
    transfer,

    //special, internal identifiers
    launchPad,
    unitCargoUnloadPoint,
    unitAssembler,
    hasFogRadius,
    steamVent,
    synced;

    public final static BlockFlag[] all = values();

    /** Values for logic only. Filters out some internal flags. AURORA: + ulocate filters — + фільтри ulocate. */
    public final static BlockFlag[] allLogic = {core, storage, generator, turret, factory, ufactory, repair, brepair, battery, reactor, drill, shield, wall, overdrive, logic, powernode, transfer};
}
