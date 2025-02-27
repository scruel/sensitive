package com.github.houbb.sensitive.core.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.ContextValueFilter;
import com.github.houbb.heaven.annotation.ThreadSafe;
import com.github.houbb.heaven.support.cache.impl.ClassFieldListCache;
import com.github.houbb.heaven.util.lang.ObjectUtil;
import com.github.houbb.heaven.util.lang.reflect.ClassUtil;
import com.github.houbb.sensitive.annotation.Sensitive;
import com.github.houbb.sensitive.annotation.SensitiveIgnore;
import com.github.houbb.sensitive.annotation.metadata.SensitiveCondition;
import com.github.houbb.sensitive.annotation.metadata.SensitiveStrategy;
import com.github.houbb.sensitive.api.*;
import com.github.houbb.sensitive.api.impl.SensitiveStrategyBuiltIn;
import com.github.houbb.sensitive.core.api.context.SensitiveContext;
import com.github.houbb.sensitive.core.exception.SensitiveRuntimeException;
import com.github.houbb.sensitive.core.support.filter.DefaultContextValueFilter;
import com.github.houbb.sensitive.core.util.ClassTypeUtil;
import com.github.houbb.sensitive.core.util.strategy.SensitiveStrategyBuiltInUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

/**
 * 脱敏服务实现类
 * <p>
 * [反射处理数组](https://blog.csdn.net/snakemoving/article/details/54287681)
 *
 * @author binbin.hou
 * @since 0.0.1
 * date 2018/12/29
 */
@ThreadSafe
public class SensitiveService<T> implements ISensitive<T> {

    @Override
    public T desCopy(T object, final ISensitiveConfig config) {
        //1. 初始化对象
        final Class<?> clazz = object.getClass();
        final SensitiveContext context = new SensitiveContext();

        //2. 深度复制对象
        final IDeepCopy deepCopy = config.deepCopy();
        final T copyObject = deepCopy.deepCopy(object);

        //3. 处理
        handleObject(context, copyObject, clazz);
        return copyObject;
    }

    @Override
    public String desJson(final T object, final ISensitiveConfig config) {
        if (ObjectUtil.isNull(object)) {
            return JSON.toJSONString(object);
        }

        final SensitiveContext context = new SensitiveContext();
        ContextValueFilter filter = new DefaultContextValueFilter(context);
        return JSON.toJSONString(object, filter);
    }

    /**
     * 处理脱敏相关信息
     *
     * @param context    执行上下文
     * @param copyObject 拷贝的新对象
     * @param clazz      class 类型
     */
    @SuppressWarnings({"unchecked"})
    private void handleObject(final SensitiveContext context,
                              final Object copyObject,
                              final Class<?> clazz) {
        // 每一个实体对应的字段，只对当前 clazz 生效。
        List<Field> fieldList = ClassFieldListCache.getInstance().get(clazz);
        context.setAllFieldList(fieldList);
        context.setCurrentObject(copyObject);

        try {
            for (Field field : fieldList) {
                SensitiveIgnore sensitiveIgnore = field.getAnnotation(SensitiveIgnore.class);
                if (null != sensitiveIgnore) {
                    continue;
                }

                prepareSensitiveContext(context, field);

                // 设置当前处理的字段
                final Class<?> fieldTypeClass = field.getType();
                if (ClassTypeUtil.isBase(fieldTypeClass)) {
                    // 处理单个字段脱敏信息
                    DesensitizeField(context, copyObject, field);
                } else if (fieldTypeClass.isArray()) {
                    // 为数组类型
                    Object array = field.get(copyObject);
                    handleArrayObject(context, array);
                } else if (ClassTypeUtil.isCollection(fieldTypeClass)) {
                    // Collection 接口的子类
                    Collection<Object> collection = (Collection<Object>) field.get(copyObject);
                    collection = handleArrayCollection(context, collection);
                    field.set(copyObject, collection);
                } else {
                    // 当作 javabean 对象处理内部字段
                    final Object fieldNewObject = field.get(copyObject);
                    handleObject(context, fieldNewObject, fieldTypeClass);
                }
            }
        } catch (IllegalAccessException | InstantiationException e) {
            throw new SensitiveRuntimeException(e);
        }
    }

    @SuppressWarnings({"unchecked"})
    private Collection<Object> handleArrayCollection(SensitiveContext context, Collection<Object> collection) throws IllegalAccessException, InstantiationException {
        Collection<Object> resultCollection = collection.getClass().newInstance();
        for (Object value : collection) {
            value = getDesensitizedObjectWrapper(context, value);
            resultCollection.add(value);
        }
        return resultCollection;
    }

    private void handleArrayObject(SensitiveContext context, Object array) {
        for (int i = 0; i < Array.getLength(array); i++) {
            Object value = Array.get(array, i);
            value = getDesensitizedObjectWrapper(context, value);
            Array.set(array, i, value);
        }
    }

    private Object getDesensitizedObjectWrapper(SensitiveContext context, Object object) {
        if (ClassTypeUtil.isBase(object.getClass())) {
            object = getDesensitizedObject(context, object);
        } else {
            handleObject(context, object, object.getClass());
        }
        return object;
    }

    /**
     * 处理需脱敏的单个对象
     * <p>
     * 1. 为了简化操作，所有的自定义注解使用多个，不生效。
     * 2. 生效顺序如下：
     * （1）Sensitive
     * （2）系统内置自定义注解
     * （3）用户自定义注解
     *
     * @param context 上下文
     * @param object  明细
     * @return 处理后的信息
     * @since 0.0.2
     */
    private Object getDesensitizedObject(final SensitiveContext context,
                                         final Object object) {
        if (null == context.getStrategy()) {
            return object;
        }
        if (null != context.getCondition() && !context.getCondition().valid(context)) {
            return object;
        }
        return context.getStrategy().des(object, context);
    }

    /**
     * 处理脱敏信息
     *
     * @param context    上下文
     * @param copyObject 复制的对象
     * @param field      当前字段
     * @since 0.0.2
     */
    private void DesensitizeField(final SensitiveContext context,
                                  final Object copyObject,
                                  final Field field) throws IllegalAccessException {
        final Object originalFieldVal = field.get(copyObject);
        field.set(copyObject, getDesensitizedObject(context, originalFieldVal));
    }

    private void prepareSensitiveContext(SensitiveContext context, Field field) throws IllegalAccessException, InstantiationException {
        Sensitive sensitive = field.getAnnotation(Sensitive.class);
        if (sensitive != null) {
            context.setCondition(sensitive.condition().newInstance());
            context.setStrategy(sensitive.strategy().newInstance());
        } else {
            // 系统内置自定义注解的处理,获取所有的注解
            parseStrategyFromField(context, field);
        }
        context.setCurrentField(field);
    }

    private void parseStrategyFromField(SensitiveContext context, Field field) {
        Annotation[] annotations = field.getAnnotations();
        for (Annotation annotation : annotations) {
            SensitiveCondition sensitiveCondition = annotation.annotationType().getAnnotation(SensitiveCondition.class);
            SensitiveStrategy sensitiveStrategy = annotation.annotationType().getAnnotation(SensitiveStrategy.class);
            if (ObjectUtil.isNotNull(sensitiveStrategy)) {
                Class<? extends IStrategy> clazz = sensitiveStrategy.value();
                if (SensitiveStrategyBuiltIn.class.equals(clazz)) {
                    context.setStrategy(SensitiveStrategyBuiltInUtil.require(annotation.annotationType()));
                } else {
                    context.setStrategy(ClassUtil.newInstance(clazz));
                }
            }
            if (ObjectUtil.isNotNull(sensitiveCondition)) {
                Class<? extends ICondition> customClass = sensitiveCondition.value();
                context.setCondition(ClassUtil.newInstance(customClass));
            }
        }
    }
}
