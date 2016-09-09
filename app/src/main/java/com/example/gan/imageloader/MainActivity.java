package com.example.gan.imageloader;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity {
    private ImageView mIvImg;
    public static final String img = "http://img4.imgtn.bdimg.com/it/u=701969124,116588908&fm=206&gp=0.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mIvImg = (ImageView) findViewById(R.id.iv_img);
        ImageLoader imageLoader = ImageLoader.build(this);
        imageLoader.bindBitmap(img, mIvImg);
    }
}
