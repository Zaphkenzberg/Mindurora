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
    /**Factory Units */
    ufactory,
    /** Repair point. */
    repair,
    /** Block Repair point. */
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
    /**Wall */
    wall,
    /**Overdrive  */
    overdrive,
    /**Block Logic  */
    logic,
    /**PowerNode */
    powernode,
    /**Transfer */
    transfer,

    //special, internal identifiers
    launchPad,
    unitCargoUnloadPoint,
    unitAssembler,
    hasFogRadius,
    steamVent,
    synced;

    public final static BlockFlag[] all = values();

    /** Values for logic only. Filters out some internal flags. */
    public final static BlockFlag[] allLogic = {core, storage, generator, turret, factory, ufactory, repair, brepair, battery, reactor, drill, shield, wall, overdrive, logic, powernode, transfer};
}
