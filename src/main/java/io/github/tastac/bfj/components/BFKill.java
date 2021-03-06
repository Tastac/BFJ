package io.github.tastac.bfj.components;

import java.util.Objects;

/**
 * <p>A count of kills for a player that has been queried from the Battlefields API.</p>
 *
 * @author Ocelot
 */
public class BFKill
{
    private final String uuid;
    private final int kills;

    public BFKill(String uuid, int kills)
    {
        this.uuid = uuid;
        this.kills = kills;
    }

    /**
     * @return The id of the player with the kills
     */
    public String getUuid()
    {
        return uuid;
    }

    /**
     * @return The count of kills the player has
     */
    public int getKills()
    {
        return kills;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BFKill bfKill = (BFKill) o;
        return this.kills == bfKill.kills &&
                this.uuid.equals(bfKill.uuid);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(this.uuid, this.kills);
    }

    @Override
    public String toString()
    {
        return "BFKill{" +
                "uuid='" + this.uuid + '\'' +
                ", kills=" + this.kills +
                '}';
    }
}
