package io.github.tastac.bfj;

import com.google.gson.*;
import io.github.tastac.bfj.components.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <p>Internal implementation of {@link BattlefieldsApi}.</p>
 *
 * @author Tastac, Ocelot
 */
public class BattlefieldsApiImpl implements BattlefieldsApi
{
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(BFServerInfo.class, new BFServerInfo.Deserializer()).create();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11";

    private final ExecutorService requestPool;
    private final Consumer<Exception> exceptionConsumer;
    private final long shutdownTimeout;
    private final TimeUnit shutdownTimeoutUnit;
    private final long cacheTime;
    private final TimeUnit cacheTimeUnit;
    private final boolean cacheErrors;
    private final Map<String, Long> timeStamps;
    private final Map<String, Object> cache;
    private final Map<String, Long> errorCache;

    public BattlefieldsApiImpl(ExecutorService requestPool, Consumer<Exception> exceptionConsumer, long shutdownTimeout, TimeUnit shutdownTimeoutUnit, long cacheTime, TimeUnit cacheTimeUnit, boolean cacheErrors)
    {
        this.requestPool = requestPool;
        this.exceptionConsumer = exceptionConsumer;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownTimeoutUnit = shutdownTimeoutUnit;
        this.cacheTime = cacheTime;
        this.cacheTimeUnit = cacheTimeUnit;
        this.cacheErrors = cacheErrors;
        this.timeStamps = new ConcurrentHashMap<>();
        this.cache = new ConcurrentHashMap<>();
        this.errorCache = new ConcurrentHashMap<>();
    }

    private static byte[] requestRaw(String url) throws URISyntaxException, IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();
        connection.addRequestProperty("User-Agent", USER_AGENT);
        try (InputStream stream = connection.getInputStream())
        {
            if (connection.getResponseCode() != 200)
                throw new IOException("Failed to connect to '" + url + "'. " + connection.getResponseCode() + " " + connection.getResponseMessage());

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int count;
            byte[] data = new byte[4096];
            while ((count = stream.read(data, 0, data.length)) != -1)
            {
                buffer.write(data, 0, count);
            }
            return buffer.toByteArray();
        }
        finally
        {
            if (connection.getResponseCode() != 200 && connection.getErrorStream() != null)
                connection.getErrorStream().close();
        }
    }

    private static JsonElement request(String url) throws URISyntaxException, IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URI(url).toURL().openConnection();
        connection.addRequestProperty("User-Agent", USER_AGENT);
        try (InputStream stream = connection.getInputStream())
        {
            if (connection.getResponseCode() != 200)
                throw new IOException("Failed to connect to '" + url + "'. " + connection.getResponseCode() + " " + connection.getResponseMessage());
            return new JsonParser().parse(new InputStreamReader(stream));
        }
        finally
        {
            if (connection.getResponseCode() != 200 && connection.getErrorStream() != null)
                connection.getErrorStream().close();
        }
    }

    private static JsonArray requestDetail(String url) throws URISyntaxException, IOException, JsonParseException
    {
        JsonObject requestObject = request(url).getAsJsonObject();
        if (!requestObject.get("status").getAsBoolean())
            throw new IOException("Failed to connect to Battlefields API: " + requestObject.get("detail").getAsString());
        return requestObject.get("detail").getAsJsonArray();
    }

    private static String getRequestUrl(BattlefieldsApiTable table, String query)
    {
        return String.format(BFJ.BF_API_URL + "?type=%s%s", table.getTable(), query);
    }

    private static String resolveQueries(String[] queries) throws IOException
    {
        if (queries.length == 0)
            return "";
        StringBuilder builder = new StringBuilder();
        for (String query : queries)
        {
            String[] splitQuery = query.split("=", 2);
            if (splitQuery.length != 2)
                throw new IOException("Invalid query: " + query);
            builder.append("&");
            builder.append(URLEncoder.encode(splitQuery[0], StandardCharsets.UTF_8.toString()));
            builder.append("=");
            builder.append(URLEncoder.encode(splitQuery[1], StandardCharsets.UTF_8.toString()));
        }
        return builder.toString();
    }

    private boolean isCacheValid(String field)
    {
        if (this.cacheTime <= 0)
            return false;
        if (!this.timeStamps.containsKey(field) && (!this.cacheErrors || !this.errorCache.containsKey(field)))
            return false;
        long timeStamp = this.timeStamps.containsKey(field) ? this.timeStamps.get(field) : this.errorCache.get(field);
        return System.currentTimeMillis() - timeStamp < TimeUnit.MILLISECONDS.convert(this.cacheTime, this.cacheTimeUnit);
    }

    @SuppressWarnings("unchecked")
    private <T> T retrieve(String field, Fetcher<T> fetcher, Supplier<T> defaultValue)
    {
        if (this.isCacheValid(field))
        {
            if (this.cache.containsKey(field))
            {
                try
                {
                    return (T) this.cache.get(field);
                }
                catch (Exception e)
                {
                    this.exceptionConsumer.accept(e);
                    this.timeStamps.remove(field);
                    this.cache.remove(field);
                }
            }
            else
            {
                return defaultValue.get();
            }
        }

        try
        {
            T value = fetcher.fetch();
            if (this.cacheTime > 0)
            {
                this.timeStamps.put(field, System.currentTimeMillis());
                this.cache.put(field, value);
            }
            return value;
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            if (this.cacheTime > 0 && this.cacheErrors)
                this.errorCache.put(field, System.currentTimeMillis());
            return defaultValue.get();
        }
    }

    @Override
    public void clearCache()
    {
        this.timeStamps.clear();
        this.cache.clear();
        this.errorCache.clear();
    }

    @Override
    public JsonArray get(BattlefieldsApiTable table, String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("custom-" + query, () -> requestDetail(getRequestUrl(table, query)), () -> null);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return null;
        }
    }

    @Override
    public String[] getServerList()
    {
        try
        {
            return this.retrieve("server_list", () -> GSON.fromJson(request(BFJ.BF_SERVER_LIST_URL), String[].class), () -> new String[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return null;
        }
    }

    @Override
    public BFServer[] getServerStatus()
    {
        try
        {
            return this.retrieve("server_status", () ->
            {
                JsonArray jsonArray = requestDetail(BFJ.BF_SERVER_STATUS_URL);
                BFServer[] servers = new BFServer[jsonArray.size()];
                for (int i = 0; i < servers.length; i++)
                {
                    JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
                    Set<Map.Entry<String, JsonElement>> entries = jsonObject.entrySet();
                    if (entries.size() != 1)
                        throw new IllegalArgumentException("Expected a single entry: " + jsonObject);
                    Map.Entry<String, JsonElement> entry = entries.iterator().next();
                    servers[i] = new BFServer(entry.getKey(), entry.getValue().getAsString());
                }
                return servers;
            }, () -> new BFServer[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return null;
        }
    }

//    @Override
//    public JsonObject getCosmeticModel(String modelName)
//    {
//        try
//        {
//            return this.retrieve("cosmetic_model-" + modelName, () -> new JsonParser().parse(new String(requestRaw(BFJ.BF_COSMETIC_URL + "model/" + modelName + ".json"))).getAsJsonObject(), () -> null);
//        }
//        catch (Exception e)
//        {
//            this.exceptionConsumer.accept(e);
//            return null;
//        }
//    }
//
//    @Override
//    public String getCosmeticModelHash(String modelName)
//    {
//        if (this.isCacheValid("cosmetic_model_hash-" + modelName))
//            return null;
//        try
//        {
//            return new String(requestRaw(BFJ.BF_COSMETIC_URL + "model/" + modelName + ".json.md5"));
//        }
//        catch (Exception e)
//        {
//            this.exceptionConsumer.accept(e);
//            if (this.cacheTime > 0 && this.cacheErrors)
//                this.errorCache.put("cosmetic_model_hash-" + modelName, System.currentTimeMillis());
//            return null;
//        }
//    }
//
//    @Override
//    public byte[] getCosmeticTexture(String textureName)
//    {
//        try
//        {
//            return this.retrieve("cosmetic_texture-" + textureName, () -> requestRaw(BFJ.BF_COSMETIC_URL + "texture/" + textureName + ".png"), () -> null);
//        }
//        catch (Exception e)
//        {
//            this.exceptionConsumer.accept(e);
//            return null;
//        }
//    }
//
//    @Override
//    public String getCosmeticTextureHash(String textureName)
//    {
//        if (this.isCacheValid("cosmetic_texture_hash-" + textureName))
//            return null;
//        try
//        {
//            return new String(requestRaw(BFJ.BF_COSMETIC_URL + "texture/" + textureName + ".png.md5"));
//        }
//        catch (Exception e)
//        {
//            this.exceptionConsumer.accept(e);
//            if (this.cacheTime > 0 && this.cacheErrors)
//                this.errorCache.put("cosmetic_texture_hash-" + textureName, System.currentTimeMillis());
//            return null;
//        }
//    }

//    @Override
//    public String getServerStatus()
//    {
//        return this.retrieve("server_status", () -> requestDetail(BFJ.BF_SERVER_STATUS_URL).get(0).getAsJsonObject().get(BFJ.BF_SERVER_HOSTNAME).getAsString(), () -> "red");
//    }

    @Override
    public BFServerInfo getServerInfo(String ip)
    {
        try
        {
            return this.retrieve("server_info-" + ip, () -> GSON.fromJson(request(BFJ.BF_SERVER_INFO_URL + ip), BFServerInfo.class), () -> null);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return null;
        }
    }

    @Override
    public BFKill[] getKills(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("kills-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.KILLS, query)), BFKill[].class), () -> new BFKill[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFKill[0];
        }
    }

    @Override
    public BFWin[] getWins(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("wins-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.WINS, query)), BFWin[].class), () -> new BFWin[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFWin[0];
        }
    }

    @Override
    public BFPlayer[] getPlayers(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("players-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.PLAYERS, query)), BFPlayer[].class), () -> new BFPlayer[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFPlayer[0];
        }
    }

    @Override
    public BFMatch[] getMatches(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("matches-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.MATCHES, query)), BFMatch[].class), () -> new BFMatch[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFMatch[0];
        }
    }

    @Override
    public BFOwnedAccessory[] getOwnedAccessories(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("owned_accessories-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.OWNED_ACCESSORIES, query)), BFOwnedAccessory[].class), () -> new BFOwnedAccessory[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFOwnedAccessory[0];
        }
    }

    @Override
    public BFAccessory[] getAccessories(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("accessories-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.ACCESSORIES, query)), BFAccessory[].class), () -> new BFAccessory[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFAccessory[0];
        }
    }

    @Override
    public BFAccessoryType[] getAccessoryTypes(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("accessory_types-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.ACCESSORY_TYPES, query)), BFAccessoryType[].class), () -> new BFAccessoryType[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFAccessoryType[0];
        }
    }

    @Override
    public BFWeapon[] getWeapons(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("weapons-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.WEAPONS, query)), BFWeapon[].class), () -> new BFWeapon[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFWeapon[0];
        }
    }

    @Override
    public BFWeaponStats[] getWeaponStats(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("weapon_stats-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.WEAPON_STATS, query)), BFWeaponStats[].class), () -> new BFWeaponStats[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFWeaponStats[0];
        }
    }

    @Override
    public BFMatchParticipant[] getMatchParticipants(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("match_participants-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.MATCH_PARTICIPANTS, query)), BFMatchParticipant[].class), () -> new BFMatchParticipant[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFMatchParticipant[0];
        }
    }

    @Override
    public BFKillInfo[] getMatchKills(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("match_kills-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.MATCH_KILLS, query)), BFKillInfo[].class), () -> new BFKillInfo[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFKillInfo[0];
        }
    }

    @Override
    public BFOwnedEmote[] getOwnedEmotes(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("owned_emotes-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.OWNED_EMOTES, query)), BFOwnedEmote[].class), () -> new BFOwnedEmote[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFOwnedEmote[0];
        }
    }

    @Override
    public BFEmote[] getEmotes(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("emotes-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.EMOTES, query)), BFEmote[].class), () -> new BFEmote[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFEmote[0];
        }
    }

    @Override
    public BFLinkedDiscord[] getLinkedDiscord(String... queries)
    {
        try
        {
            String query = resolveQueries(queries);
            return this.retrieve("linked_discord-" + query, () -> GSON.fromJson(requestDetail(getRequestUrl(BattlefieldsApiTable.LINKED_DISCORD, query)), BFLinkedDiscord[].class), () -> new BFLinkedDiscord[0]);
        }
        catch (Exception e)
        {
            this.exceptionConsumer.accept(e);
            return new BFLinkedDiscord[0];
        }
    }

    @Override
    public ExecutorService getExecutor()
    {
        return requestPool;
    }

    @Override
    public boolean shutdown() throws InterruptedException
    {
        this.requestPool.shutdown();
        return this.requestPool.awaitTermination(this.shutdownTimeout, this.shutdownTimeoutUnit);
    }

    /**
     * Fetches data from a server.
     *
     * @param <T> The type of data to fetch
     * @author Ocelot
     */
    private interface Fetcher<T>
    {
        /**
         * @return The data read
         * @throws Exception If the data could not be read for any reason
         */
        T fetch() throws Exception;
    }
}
