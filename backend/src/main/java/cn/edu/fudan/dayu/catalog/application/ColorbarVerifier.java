package cn.edu.fudan.dayu.catalog.application;

/** 发布时检查色标是否在受控根目录中且可读，拒绝路径逃逸。 */
public interface ColorbarVerifier {
    void verify(String relativePath);
}
