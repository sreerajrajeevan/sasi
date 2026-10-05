package com.sree.sasi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.sree.sasi.R

/** Sasi rendered from the same body + face vectors the overlay uses. */
@Composable
fun SasiFace(size: Dp, faceRes: Int = R.drawable.sasi_face_normal) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = R.drawable.sasi_body),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
        Image(
            painter = painterResource(id = faceRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
        )
    }
}
