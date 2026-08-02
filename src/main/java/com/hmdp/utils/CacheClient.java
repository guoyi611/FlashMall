package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    //原始缓存，存在缓存穿透，击穿，雪崩的风险
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }


    //逻辑过期
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透，缓存空值
    public <R,ID> R queryWithPassThrough(
            String keyPreFix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit
    ){
        String key = keyPreFix + id;
       //根据id查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否未空
        if (StrUtil.isNotBlank(json)){
            //有缓存,直接返回
            return JSONUtil.toBean(json,type);
        }
        if (json!=null){
            return null;
        }
        //无缓存，查询数据库
        R r = dbFallback.apply(id);
        //判断数据库查询结果是否未空
        //为空就是虚假id
        if ((r==null)){
            stringRedisTemplate.opsForValue().set(keyPreFix+id,"",time,unit);
            return null;
        }
        stringRedisTemplate.opsForValue().set(keyPreFix+id,JSONUtil.toJsonStr(r),time,unit);
        return r;
    }

    //手动创建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    //逻辑过期处理下的缓存查询
    public <R,ID> R queryWithLogicExpire(
            String keyPreFix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //查询缓存
        String json = stringRedisTemplate.opsForValue().get(keyPreFix + id);
        //检查是否命中
        if (StrUtil.isBlank(json)){
            //未命中返回空
            return null;
        }
        //命中检查是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return r;
        }
        //过期，获取锁
        //成功开新线程重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //再次检查缓存是否过期
                    String recheckJson = stringRedisTemplate.opsForValue().get(keyPreFix + id);
                    RedisData recheckDate = JSONUtil.toBean(recheckJson, RedisData.class);
                    LocalDateTime recheckDateExpireTime = recheckDate.getExpireTime();
                    if (recheckDateExpireTime.isAfter(LocalDateTime.now())){
                        return;
                    }
                    R newR = dbFallback.apply(id);
                    setWithLogicExpire(keyPreFix + id,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //失败返回旧数据
        return r;
    }

    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + key);
    }
}
