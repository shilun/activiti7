package com.github.yiuman.citrus.support.crud.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.github.yiuman.citrus.support.crud.CrudHelper;
import com.github.yiuman.citrus.support.crud.mapper.CrudMapper;
import com.github.yiuman.citrus.support.crud.mapper.TreeMapper;
import com.github.yiuman.citrus.support.crud.query.Query;
import com.github.yiuman.citrus.support.model.BasePreOrderTree;
import com.github.yiuman.citrus.support.model.Tree;
import com.github.yiuman.citrus.support.utils.LambdaUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 左右值预遍历树逻辑层
 *
 * @param <E> 实体类型
 * @param <K> 主键类型
 * @author yiuman
 * @date 2020/4/15
 */
public abstract class BasePreOrderTreeService<E extends BasePreOrderTree<E, K>, K extends Serializable>
        extends BaseService<E, K> implements TreeCrudService<E, K> {

    private static final String UPDATE_ADD_FORMAT = "%s=%s+%s";

    private static final String UPDATE_REDUCTION_FORMAT = "%s=%s-%s";

    @Override
    protected CrudMapper<E> getMapper() {
        return CrudHelper.getTreeMapper(getEntityType());
    }

    protected TreeMapper<E> getTreeMapper() {
        return (TreeMapper<E>) getMapper();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean beforeSave(E entity) throws Exception {
        if (Objects.nonNull(entity.getId())) {
            return true;
        }
        //1.获取当前父节点
        E parent = Optional
                .ofNullable(get(entity.getParentId()))
                .orElse(getRoot());
        int rightValue = 1;
        int deep = 1;
        //4.更新父节点
        if (!Objects.equals(entity.getId(), parent.getId())) {
            beforeSaveOrUpdate(parent);
            rightValue = parent.getRightValue();
            deep = parent.getDeep() + 1;
            parent.setRightValue(rightValue + 2);
            save(parent);
            entity.setParentId(parent.getId());
        } else {
            entity.setParentId(null);
        }

        //5.设置当前节点的左右值
        entity.setLeftValue(rightValue);
        entity.setRightValue(rightValue + 1);
        entity.setDeep(deep);
        return true;
        //Over
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean beforeRemove(E entity) {
        //1.更新所有右值小于当前父节点右值的节点左值 -2
        this.beforeDeleteOrMove(entity);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void batchRemove(Iterable<K> keys) {
        getMapper().deleteBatchIds(StreamSupport.stream(keys.spliterator(), false).collect(Collectors.toList()));
    }

    @Override
    public E getRoot() {
        return getMapper().selectOne(Wrappers.<E>query().isNull(getParentField()));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public synchronized void reInit() throws Exception {
        reInit(getRoot());
    }

    @Transactional(rollbackFor = Exception.class)
    protected void reInit(E current) throws Exception {
        save(current);
        List<E> children = loadByParent(current.getId());
        if (!CollectionUtils.isEmpty(children)) {
            children.forEach(LambdaUtils.consumerWrapper(this::reInit));
        }
    }

    @Override
    public E load(boolean isLazy) {
        E current = getRoot();
        if (current == null) {
            return null;
        }
        load(current, isLazy);
        return current;
    }

    @Override
    public E treeQuery(Query query) {
        if (Objects.isNull(query)) {
            return load(false);
        }
        //查询符合条件的列表
        List<E> list = list(query);
        if (CollectionUtils.isEmpty(list)) {
            return null;
        }
        //找到列表ID
        List<K> ids = list.parallelStream().map(Tree::getId).collect(Collectors.toList());
        TableInfo table = SqlHelper.table(getEntityType());

        //将查询到的列表的项的所有父节点查出来
        String parentSql = "t1.leftValue > t2. leftValue and t1.rightValue < t2.rightValue"
                .replaceAll("leftValue", getLeftField())
                .replaceAll("rightValue", getRightField());
        list.addAll(getTreeMapper().treeLink(table.getTableName(), Wrappers.<E>query().apply(parentSql).in("t1." + table.getKeyColumn(), ids)));
        final E root = getRoot();
        //传list进去前需要去重,并排除根节点
        listToTree(root, list.parallelStream().distinct().filter(item -> !Objects.equals(item.getId(), root.getId())).collect(Collectors.toList()));
        return root;
    }

    /**
     * 此处记得去重，实体记得重写equals&hasCode
     *
     * @param current 挂载的节点
     * @param list    节点列表
     */
    protected void listToTree(E current, List<E> list) {
        Map<String, List<E>> parentIdChildrenMap = list.stream()
                .collect(Collectors.groupingBy(item -> StrUtil.toString(item.getParentId())));
        list.forEach(entity -> entity.setChildren(parentIdChildrenMap.get(StrUtil.toString(entity.getId()))
                .stream().distinct().collect(Collectors.toList())));
        current.setChildren(
                list.stream()
                        .filter(entity -> Objects.equals(StrUtil.toString(entity.getParentId()), StrUtil.toString(current.getId())))
                        .collect(Collectors.toList())
        );
    }

    @Override
    public void load(E current) {
        load(current, true);
    }

    @Override
    public void load(E current, boolean isLazy) {
        if (isLazy) {
            List<E> children = loadByParent(current.getId());
            children.parallelStream().forEach(childNode -> {
                if (!childNode.isLeaf()) {
                    childNode.setChildren(new ArrayList<>());
                }
            });
            current.setChildren(children);
        } else {
            listToTree(current, list());
        }
    }

    @Override
    public List<E> loadByParent(K parentKey) {
        return getTreeMapper().selectList(Wrappers.<E>query().eq(getParentField(), parentKey));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void move(E current, K moveTo) throws Exception {
        //更新树
        this.beforeDeleteOrMove(current);
        //变更父ID
        current.setParentId(moveTo);
        save(current);

        List<E> children = children(current);
        //子节点不为空时重新生成左右值
        if (!CollectionUtils.isEmpty(children)) {
            children.forEach(LambdaUtils.consumerWrapper(this::save));
        }
    }

    @Override
    public List<E> children(E current) {
        // target.left > this.left  and target.right < this.right
        return getTreeMapper().selectList(Wrappers.<E>query()
                .gt(getLeftField(), current.getLeftValue())
                .lt(getRightField(), current.getRightValue()));
    }

    @Override
    public List<E> children(E current, int deep) {
        return getTreeMapper().selectList(Wrappers.<E>query()
                .gt(getLeftField(), current.getLeftValue())
                .lt(getRightField(), current.getRightValue())
                .eq(getDeepField(), deep));
    }

    @Override
    public List<E> parents(E current) {
        return getTreeMapper().selectList(Wrappers.<E>query()
                .gt(getRightField(), current.getRightValue())
                .le(getLeftField(), current.getLeftValue()));
    }

    @Override
    public E parent(E current, int high) {
        return getTreeMapper().selectOne(Wrappers.<E>query()
                .gt(getRightField(), current.getRightValue())
                .le(getLeftField(), current.getLeftValue())
                .eq(getDeepField(), high));
    }

    @Override
    public List<E> siblings(E current) {
        return getTreeMapper().selectList(Wrappers.<E>query().eq(getParentField(), current.getParentId()));
    }

    /**
     * 当前节点保存前的做的操作
     *
     * @param parent 当前节点的父节点
     */
    private void beforeSaveOrUpdate(E parent) {

        //2.更新所有左值大于当前父节点右值的节点左值 +2
        getTreeMapper().update(null, Wrappers.<E>update()
                .setSql(String.format(UPDATE_ADD_FORMAT, getLeftField(), getLeftField(), 2))
                .gt(getLeftField(), parent.getRightValue()));

        //3.更新所有右值大于当前父节点右值的节点右值 +2
        getTreeMapper().update(null, Wrappers.<E>update()
                .setSql(String.format(UPDATE_ADD_FORMAT, getRightField(), getRightField(), 2))
                .gt(getRightField(), parent.getRightValue()));
    }

    /**
     * 删除或移动前做的操作
     *
     * @param current 当前节点
     */
    private void beforeDeleteOrMove(E current) {
        //1.更新所有右值小于当前父节点右值的节点左值 -2
        getTreeMapper().update(null, Wrappers.<E>update()
                .setSql(String.format(UPDATE_REDUCTION_FORMAT, getLeftField(), getLeftField(), 2))
                .gt(getLeftField(), current.getRightValue()));

        //2.更新所有右值小于当前父节点右值的节点右值 +2
        getTreeMapper().update(null, Wrappers.<E>update()
                .setSql(String.format(UPDATE_REDUCTION_FORMAT, getRightField(), getRightField(), 2))
                .gt(getRightField(), current.getRightValue()));
    }

}
